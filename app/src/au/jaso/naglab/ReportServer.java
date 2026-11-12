package au.jaso.naglab;

import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

/**
 * Hands the battery report to a phone: while the report box is open, a phone on the same Wi-Fi
 * or hotspot as the car can open http://car-address:8765 and gets the page (and /trips.csv).
 * Runs only while that box is open, serves those two things and nothing else, never reads
 * anything the phone sends beyond the first line.
 */
final class ReportServer {
    static final int PORT = 8765;
    private final byte[] html, csv;
    private volatile ServerSocket server;

    ReportServer(String html, byte[] csv) {
        this.html = html.getBytes(StandardCharsets.UTF_8);
        this.csv = csv;
    }

    /** The car's addresses a phone could reach, best first: the Wi-Fi the car has joined, then
     *  the car's own hotspot (its address there ends in .1). Empty when it has neither. */
    static java.util.List<String> addresses() {
        java.util.List<String> joined = new java.util.ArrayList<>(), own = new java.util.ArrayList<>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName();
                if (!(name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan"))) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String h = a.getHostAddress();
                    (h.endsWith(".1") ? own : joined).add(h);
                }
            }
        } catch (Exception ignored) {
        }
        joined.addAll(own);
        return joined;
    }

    boolean start() {
        try {
            server = new ServerSocket(PORT);
        } catch (Exception e) {
            return false;
        }
        Thread t = new Thread(() -> {
            ServerSocket s = server;
            while (s != null && !s.isClosed()) {
                try (Socket c = s.accept()) {
                    c.setSoTimeout(3000);
                    byte[] buf = new byte[1024];
                    int n = c.getInputStream().read(buf);
                    String first = n > 0 ? new String(buf, 0, n, StandardCharsets.US_ASCII) : "";
                    boolean wantCsv = first.startsWith("GET /trips.csv");
                    byte[] body = wantCsv ? csv : html;
                    OutputStream o = c.getOutputStream();
                    o.write(("HTTP/1.0 200 OK\r\nContent-Type: " + (wantCsv ? "text/csv" : "text/html; charset=utf-8")
                            + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    o.write(body);
                    o.flush();
                } catch (Exception e) {
                    // closed (stop) or a phone that hung up: carry on or fall out of the loop
                }
            }
        }, "naglab-report");
        t.setDaemon(true);
        t.start();
        return true;
    }

    void stop() {
        ServerSocket s = server;
        server = null;
        try {
            if (s != null) s.close();
        } catch (Exception ignored) {
        }
    }
}
