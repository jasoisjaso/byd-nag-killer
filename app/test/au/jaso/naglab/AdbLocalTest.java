package au.jaso.naglab;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;

/** Mac-side tests for AdbLocal. Run by test/run.sh (plain JVM, no Android). */
public class AdbLocalTest {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    public static void main(String[] a) throws Exception {
        // 1. public-key encoding == real adb's ~/.android/adbkey.pub
        String home = System.getProperty("user.home");
        String pem = new String(Files.readAllBytes(Paths.get(home, ".android", "adbkey")), StandardCharsets.US_ASCII);
        String b64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
        RSAPublicKey pub = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
        String real = new String(Files.readAllBytes(Paths.get(home, ".android", "adbkey.pub")),
                StandardCharsets.US_ASCII).trim().split(" ")[0];
        check(AdbLocal.Key.encodePublic(pub).equals(real), "encodePublic matches real adbkey.pub");

        // 2. signature == standard PKCS#1 v1.5 over SHA-1 DigestInfo(token)
        AdbLocal.Key key = new AdbLocal.Key(new KeyPair(pub, priv));
        byte[] token = new byte[20];
        new SecureRandom().nextBytes(token);
        byte[] mine = key.sign(token);
        byte[] di = concat(new byte[]{0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
                0x05, 0x00, 0x04, 0x14}, token);
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(priv);
        s.update(di);
        check(Arrays.equals(mine, s.sign()), "sign() == NONEwithRSA(DigestInfo||token)");

        // 3. key save/load round trip
        AdbLocal.Key gen = AdbLocal.Key.generate();
        AdbLocal.Key back = AdbLocal.Key.load(gen.privateBytes(), gen.publicBytes());
        check(back.publicKeyString("x").equals(gen.publicKeyString("x")), "generate/save/load round trip");

        // 4. handshake + shell against a mock adbd: unknown key first (pubkey prompt), then known key
        MockAdbd d = new MockAdbd();
        d.start();
        final boolean[] prompted = {false};
        // 4a. automatic mode (offerKey=false) must NOT send the public key -> no dialog on the car
        try (AdbLocal c = AdbLocal.connect("127.0.0.1", d.port, gen, "naglab@test", 3000, false, 5000,
                new AdbLocal.AuthPrompt() {
                    public void onWaitingForUser() {
                        prompted[0] = true;
                    }
                })) {
            check(false, "unknown key without offerKey must fail");
        } catch (AdbLocal.KeyNotTrustedException e) {
            check(!prompted[0] && d.known == null, "unknown key, offerKey=false -> KeyNotTrusted, no pubkey sent");
        }
        try (AdbLocal c = AdbLocal.connect("127.0.0.1", d.port, gen, "naglab@test", 3000, true, 5000,
                new AdbLocal.AuthPrompt() {
                    public void onWaitingForUser() {
                        prompted[0] = true;
                    }
                })) {
            check(prompted[0], "unknown key -> RSAPUBLICKEY sent, user prompt callback fired");
            String out = c.shell("echo hello");
            check(out.equals("ran: echo hello\n"), "shell() returns command output (" + out.trim() + ")");
            String out2 = c.shell("second");
            check(out2.equals("ran: second\n"), "second shell() on same connection");
        }
        prompted[0] = false;
        try (AdbLocal c = AdbLocal.connect("127.0.0.1", d.port, gen, "naglab@test", 3000, false, 5000,
                new AdbLocal.AuthPrompt() {
                    public void onWaitingForUser() {
                        prompted[0] = true;
                    }
                })) {
            check(!prompted[0], "known key, offerKey=false -> mock adbd RSA-verified our signature, no prompt");
            check(c.shell("x").equals("ran: x\n"), "shell() after signature auth");
        }

        // 5. connection refused (Wireless ADB off) surfaces as ConnectException, socket closed
        try (ServerSocket closed = new ServerSocket(0)) {
            int deadPort = closed.getLocalPort();
            closed.close();
            try {
                AdbLocal.connect("127.0.0.1", deadPort, gen, "naglab@test", 1000, false, 1000, null);
                check(false, "connect to closed port must fail");
            } catch (java.net.ConnectException e) {
                check(true, "ADB off -> ConnectException");
            }
        }

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static byte[] concat(byte[] x, byte[] y) {
        byte[] r = Arrays.copyOf(x, x.length + y.length);
        System.arraycopy(y, 0, r, x.length, y.length);
        return r;
    }

    /** Mock adbd: real token/signature check (like adbd's RSA_verify), parses the pubkey struct. */
    static final class MockAdbd extends Thread {
        final ServerSocket ss;
        final int port;
        RSAPublicKey known;      // "Always allow" list

        MockAdbd() throws Exception {
            ss = new ServerSocket(0);
            port = ss.getLocalPort();
            setDaemon(true);
        }

        public void run() {
            try {
                while (true) {
                    try (Socket s = ss.accept()) {
                        serve(s);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }

        void send(OutputStream o, int cmd, int a0, int a1, byte[] d) throws Exception {
            ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            h.putInt(cmd).putInt(a0).putInt(a1).putInt(d.length).putInt(0).putInt(~cmd);
            o.write(h.array());
            o.write(d);
            o.flush();
        }

        int[] hdr = new int[6];

        byte[] read(DataInputStream in) throws Exception {
            byte[] hb = new byte[24];
            in.readFully(hb);
            ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 6; i++) hdr[i] = h.getInt();
            if (hdr[5] != ~hdr[0]) throw new Exception("bad magic");
            byte[] d = new byte[hdr[3]];
            in.readFully(d);
            if (AdbLocal.checksum(d) != hdr[4]) throw new Exception("bad checksum");
            return d;
        }

        boolean verify(RSAPublicKey k, byte[] token, byte[] sig) throws Exception {
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(Cipher.DECRYPT_MODE, k);
            byte[] dec = c.doFinal(sig);
            byte[] want = concat(new byte[]{0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
                    0x1a, 0x05, 0x00, 0x04, 0x14}, token);
            return Arrays.equals(dec, want);
        }

        static RSAPublicKey parse(String text) throws Exception {
            byte[] b = Base64.getDecoder().decode(text.trim().split(" ")[0]);
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            int words = bb.getInt();
            bb.getInt();
            byte[] be = new byte[words * 4];
            for (int i = 0; i < words; i++) {
                int w = bb.getInt();
                int p = (words - 1 - i) * 4;
                be[p] = (byte) (w >>> 24);
                be[p + 1] = (byte) (w >>> 16);
                be[p + 2] = (byte) (w >>> 8);
                be[p + 3] = (byte) w;
            }
            bb.position(8 + words * 8);
            int e = bb.getInt();
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(new BigInteger(1, be), BigInteger.valueOf(e)));
        }

        void serve(Socket s) throws Exception {
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream o = s.getOutputStream();
            read(in);
            if (hdr[0] != AdbLocal.A_CNXN) throw new Exception("expected CNXN");
            byte[] token = new byte[20];
            new SecureRandom().nextBytes(token);
            send(o, AdbLocal.A_AUTH, 1, 0, token);
            while (true) {
                byte[] d = read(in);
                if (hdr[0] != AdbLocal.A_AUTH) throw new Exception("expected AUTH");
                if (hdr[1] == 2) {
                    if (known != null && verify(known, token, d)) break;
                    new SecureRandom().nextBytes(token);
                    send(o, AdbLocal.A_AUTH, 1, 0, token);
                } else if (hdr[1] == 3) {
                    known = parse(new String(d, 0, d.length - 1, StandardCharsets.UTF_8));  // "Always allow"
                    break;
                }
            }
            send(o, AdbLocal.A_CNXN, 0x01000000, 4096, AdbLocal.cstr("device::ro.product.name=mock;"));
            int remoteSeq = 100;
            while (true) {
                byte[] d = read(in);
                if (hdr[0] == AdbLocal.A_OPEN) {
                    int local = hdr[1], mine = remoteSeq++;
                    String svc = new String(d, 0, d.length - 1, StandardCharsets.UTF_8);
                    send(o, AdbLocal.A_OKAY, mine, local, new byte[0]);
                    String cmd = svc.substring("shell:".length());
                    byte[] payload = ("ran: " + cmd + "\n").getBytes(StandardCharsets.UTF_8);
                    // split in two WRTEs to exercise reassembly
                    int h = payload.length / 2;
                    send(o, AdbLocal.A_WRTE, mine, local, Arrays.copyOfRange(payload, 0, h));
                    read(in);  // OKAY
                    send(o, AdbLocal.A_WRTE, mine, local, Arrays.copyOfRange(payload, h, payload.length));
                    read(in);  // OKAY
                    send(o, AdbLocal.A_CLSE, mine, local, new byte[0]);
                    read(in);  // CLSE
                }
            }
        }
    }
}
