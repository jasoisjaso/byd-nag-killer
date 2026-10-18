package au.jaso.naglab;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Minimal ADB client (classic TCP protocol, RSA auth) so the app can talk to the car's own
 * adbd on 127.0.0.1:5555 and start the shell-uid watchdog - the same trick Overdrive uses.
 * Pure Java (no android.*) so it is unit-tested on the Mac against the real adb key format.
 *
 * Protocol: https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/protocol.txt
 */
final class AdbLocal implements AutoCloseable {

    /** Called when adbd does not know our key yet and the car shows "Allow USB debugging?". */
    interface AuthPrompt {
        void onWaitingForUser();
    }

    static final int A_CNXN = 0x4e584e43;
    static final int A_AUTH = 0x48545541;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;

    static final int AUTH_TOKEN = 1;
    static final int AUTH_SIGNATURE = 2;
    static final int AUTH_RSAPUBLICKEY = 3;

    static final int VERSION = 0x01000000;
    static final int MAX_DATA = 4096;

    // ---------------------------------------------------------------- key

    static final class Key {
        final KeyPair pair;

        Key(KeyPair pair) {
            this.pair = pair;
        }

        static Key generate() throws Exception {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return new Key(g.generateKeyPair());
        }

        static Key load(byte[] pkcs8, byte[] x509) throws Exception {
            KeyFactory f = KeyFactory.getInstance("RSA");
            PrivateKey priv = f.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            java.security.PublicKey pub = f.generatePublic(new X509EncodedKeySpec(x509));
            return new Key(new KeyPair(pub, priv));
        }

        byte[] privateBytes() {
            return pair.getPrivate().getEncoded();
        }

        byte[] publicBytes() {
            return pair.getPublic().getEncoded();
        }

        /** PKCS#1 v1.5 type-1 block with the SHA-1 DigestInfo prefix: adbd treats the
         *  20-byte token as a SHA-1 digest (RSA_sign(NID_sha1, token)). */
        byte[] sign(byte[] token) throws Exception {
            // NONEwithRSA = PKCS#1 v1.5 padding over raw input, so we only supply the
            // DigestInfo. Portable across SunJCE (Mac tests) and Conscrypt (the car).
            byte[] prefix = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
                    0x05, 0x00, 0x04, 0x14};
            byte[] digestInfo = new byte[prefix.length + token.length];
            System.arraycopy(prefix, 0, digestInfo, 0, prefix.length);
            System.arraycopy(token, 0, digestInfo, prefix.length, token.length);
            Signature s = Signature.getInstance("NONEwithRSA");
            s.initSign(pair.getPrivate());
            s.update(digestInfo);
            return s.sign();
        }

        /** adb's public key text: base64(android RSAPublicKey struct) + " " + name. */
        String publicKeyString(String name) {
            return encodePublic((RSAPublicKey) pair.getPublic()) + " " + name;
        }

        static String encodePublic(RSAPublicKey pub) {
            final int words = pub.getModulus().bitLength() / 32;   // 64 for 2048-bit
            BigInteger r32 = BigInteger.ZERO.setBit(32);
            BigInteger n = pub.getModulus();
            BigInteger r = BigInteger.ZERO.setBit(words * 32);
            BigInteger rr = r.modPow(BigInteger.valueOf(2), n);
            BigInteger n0inv = n.remainder(r32).modInverse(r32).negate();
            ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4)
                    .order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(words);
            b.putInt(n0inv.intValue());
            BigInteger t = n;
            for (int i = 0; i < words; i++) {
                BigInteger[] qr = t.divideAndRemainder(r32);
                t = qr[0];
                b.putInt(qr[1].intValue());
            }
            t = rr;
            for (int i = 0; i < words; i++) {
                BigInteger[] qr = t.divideAndRemainder(r32);
                t = qr[0];
                b.putInt(qr[1].intValue());
            }
            b.putInt(pub.getPublicExponent().intValue());
            return Base64.getEncoder().encodeToString(b.array());
        }
    }

    // ---------------------------------------------------------------- messages

    static final class Msg {
        final int cmd, arg0, arg1;
        final byte[] data;

        Msg(int cmd, int arg0, int arg1, byte[] data) {
            this.cmd = cmd;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data == null ? new byte[0] : data;
        }
    }

    private final Socket sock;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    private AdbLocal(Socket s) throws IOException {
        sock = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
    }

    static byte[] cstr(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        byte[] r = new byte[b.length + 1];
        System.arraycopy(b, 0, r, 0, b.length);
        return r;
    }

    static int checksum(byte[] d) {
        int s = 0;
        for (byte x : d) s += x & 0xff;
        return s;
    }

    void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        if (data == null) data = new byte[0];
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length)
                .putInt(checksum(data)).putInt(~cmd);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    Msg read() throws IOException {
        byte[] hb = new byte[24];
        in.readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = h.getInt(), a0 = h.getInt(), a1 = h.getInt(), len = h.getInt();
        h.getInt();                      // checksum (ignored, newer adbd sends 0)
        int magic = h.getInt();
        if (magic != ~cmd) throw new IOException("bad adb magic");
        if (len < 0 || len > 1024 * 1024) throw new IOException("bad adb length " + len);
        byte[] d = new byte[len];
        in.readFully(d);
        return new Msg(cmd, a0, a1, d);
    }

    // ---------------------------------------------------------------- connect

    /** Thrown when adbd does not trust our key and we were told not to offer it. */
    static final class KeyNotTrustedException extends IOException {
        KeyNotTrustedException() {
            super("adb key not trusted yet");
        }
    }

    /**
     * Connect and authenticate. If the car has never seen this key: with offerKey it shows
     * the "Allow USB debugging?" dialog and we wait up to userWaitMs for the owner to tap
     * Allow; without offerKey we give up quietly (KeyNotTrustedException) so an automatic
     * attempt never pops a dialog on the driver's screen.
     */
    static AdbLocal connect(String host, int port, Key key, String keyName, int connectTimeoutMs,
                            boolean offerKey, int userWaitMs, AuthPrompt prompt) throws Exception {
        Socket s = new Socket();
        AdbLocal a;
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            s.setSoTimeout(10_000);
            a = new AdbLocal(s);
        } catch (Exception e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
        try {
            a.send(A_CNXN, VERSION, MAX_DATA, cstr("host::naglab"));
            boolean sentSig = false, sentKey = false;
            while (true) {
                Msg m;
                try {
                    m = a.read();
                } catch (SocketTimeoutException e) {
                    throw new IOException(sentKey ? "Allow USB debugging was not accepted in time"
                            : "adbd did not answer");
                }
                if (m.cmd == A_CNXN) {
                    s.setSoTimeout(15_000);
                    return a;
                }
                if (m.cmd != A_AUTH || m.arg0 != AUTH_TOKEN) {
                    throw new IOException("unexpected adb message 0x" + Integer.toHexString(m.cmd));
                }
                if (!sentSig) {
                    a.send(A_AUTH, AUTH_SIGNATURE, 0, key.sign(m.data));
                    sentSig = true;
                } else if (!sentKey) {
                    if (!offerKey) throw new KeyNotTrustedException();
                    a.send(A_AUTH, AUTH_RSAPUBLICKEY, 0, cstr(key.publicKeyString(keyName)));
                    sentKey = true;
                    s.setSoTimeout(userWaitMs);
                    if (prompt != null) prompt.onWaitingForUser();
                } else {
                    throw new IOException("adbd rejected the key");
                }
            }
        } catch (Exception e) {
            a.close();
            throw e;
        }
    }

    /** Run a shell command (as uid 2000) and return its output. */
    String shell(String command) throws IOException {
        int local = nextLocalId++;
        send(A_OPEN, local, 0, cstr("shell:" + command));
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Msg m = read();
            if (m.arg1 != local) continue;          // other stream
            if (m.cmd == A_OKAY) {
                remote = m.arg0;
            } else if (m.cmd == A_WRTE) {
                remote = m.arg0;
                outBuf.write(m.data);
                send(A_OKAY, local, remote, null);
            } else if (m.cmd == A_CLSE) {
                if (remote != 0) send(A_CLSE, local, remote, null);
                return new String(outBuf.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    @Override
    public void close() {
        try {
            sock.close();
        } catch (IOException ignored) {
        }
    }
}
