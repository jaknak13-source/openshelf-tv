package tv.openshelf.keeper.core;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * In-process stand-in for adbd's TCP transport, written separately from AdbConnection.
 * Verifies signatures like adbd (PKCS#1 v1.5, SHA-1 DigestInfo over the raw token), checks the offered
 * public-key blob for internal consistency, splits shell output over several WRTEs and simulates pm.
 */
final class FakeAdbd implements Closeable {
    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f;
    static final int OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;

    final Set<String> installed = Collections.synchronizedSet(new HashSet<>());
    final Set<String> disabled = Collections.synchronizedSet(new HashSet<>());
    final Set<String> refuseDisable = Collections.synchronizedSet(new HashSet<>());
    final List<String> commands = Collections.synchronizedList(new ArrayList<>());
    final List<String> problems = Collections.synchronizedList(new ArrayList<>());
    /** Commands (without "shell:") whose OPEN is refused with CLSE(0, local). */
    final Set<String> refuse = Collections.synchronizedSet(new HashSet<>());
    /** Commands (without "shell:") whose output is replaced, e.g. by pm error text. */
    final Map<String, String> outputOverride = Collections.synchronizedMap(new HashMap<>());
    enum Frame { NORMAL, BAD_MAGIC, HUGE_LENGTH }
    /** How the reply to an OPEN is framed. */
    volatile Frame frame = Frame.NORMAL;
    volatile int chunk = 37;
    /** Extra CLSE traffic real adbd produces for streams the client already finished. */
    enum StaleClose { NONE, DUPLICATE_IMMEDIATELY, AFTER_CLIENT_CLSE, AFTER_NEXT_OPEN }
    volatile StaleClose staleClose = StaleClose.NONE;
    volatile RSAPublicKey authorized;
    volatile boolean userAccepts;
    volatile String offeredPayload;
    volatile int connections;

    private final ServerSocket server;
    private final SecureRandom random = new SecureRandom();

    FakeAdbd() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::serve, "fake-adbd");
        t.setDaemon(true);
        t.start();
    }

    int port() {
        return server.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket s = server.accept()) {
                connections++;
                s.setSoTimeout(10_000);
                handle(new DataInputStream(s.getInputStream()), s.getOutputStream());
            } catch (EOFException | SocketException e) {
                // client closed the connection, or the server socket was closed
            } catch (IOException | GeneralSecurityException e) {
                problems.add(e.toString());
            }
        }
    }

    private void handle(DataInputStream in, OutputStream out) throws IOException, GeneralSecurityException {
        Msg m = read(in);
        if (m.cmd != CNXN || !new String(m.data, StandardCharsets.US_ASCII).equals("host::\0")) {
            problems.add("first message is not CNXN host::");
            return;
        }
        byte[] token = newToken();
        send(out, AUTH, 1, 0, token);
        while (true) {
            m = read(in);
            if (m.cmd != AUTH) {
                problems.add("expected AUTH, got " + Integer.toHexString(m.cmd));
                return;
            }
            if (m.arg0 == 2) {
                if (authorized != null && verify(authorized, token, m.data)) {
                    break;
                }
                token = newToken();
                send(out, AUTH, 1, 0, token);
            } else if (m.arg0 == 3) {
                offeredPayload = new String(m.data, StandardCharsets.US_ASCII);
                RSAPublicKey offered = decodePayload(m.data);
                if (!userAccepts) {
                    while (true) {
                        read(in); // prompt stays open until the client gives up
                    }
                }
                authorized = offered;
                break;
            } else {
                problems.add("unknown AUTH type " + m.arg0);
                return;
            }
        }
        send(out, CNXN, 0x01000000, 4096, "device::ro.product.name=fake;\0".getBytes(StandardCharsets.US_ASCII));

        int nextRemote = 100;
        int prevRemote = 0;
        int prevLocal = 0;
        while (true) {
            m = read(in);
            if (m.cmd == CLSE) {
                continue;
            }
            if (m.cmd != OPEN) {
                problems.add("expected OPEN, got " + Integer.toHexString(m.cmd));
                return;
            }
            String raw = new String(m.data, StandardCharsets.UTF_8);
            if (!raw.endsWith("\0")) {
                problems.add("OPEN payload not NUL-terminated");
            }
            String cmd = raw.endsWith("\0") ? raw.substring(0, raw.length() - 1) : raw;
            commands.add(cmd);
            int remote = nextRemote++;
            int local = m.arg0;
            String bare = cmd.startsWith("shell:") ? cmd.substring("shell:".length()) : cmd;
            if (staleClose == StaleClose.AFTER_NEXT_OPEN && prevLocal != 0) {
                send(out, CLSE, prevRemote, prevLocal, new byte[0]);
                send(out, WRTE, prevRemote, prevLocal, "late output\n".getBytes(StandardCharsets.UTF_8));
                send(out, OKAY, prevRemote, prevLocal, new byte[0]);
            }
            if (refuse.contains(bare)) {
                send(out, CLSE, 0, local, new byte[0]);
                continue;
            }
            if (frame != Frame.NORMAL) {
                ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
                h.putInt(OKAY).putInt(remote).putInt(local)
                        .putInt(frame == Frame.HUGE_LENGTH ? 64 * 1024 * 1024 : 0).putInt(0)
                        .putInt(frame == Frame.BAD_MAGIC ? 0x12345678 : ~OKAY);
                out.write(h.array());
                out.flush();
                while (true) {
                    read(in); // client must give up on its own
                }
            }
            send(out, OKAY, remote, local, new byte[0]);
            String text = outputOverride.containsKey(bare) ? outputOverride.get(bare) : execute(cmd);
            byte[] output = text.getBytes(StandardCharsets.UTF_8);
            int size = chunk;
            for (int off = 0; off < output.length; off += size) {
                byte[] chunk = java.util.Arrays.copyOfRange(output, off, Math.min(output.length, off + size));
                send(out, WRTE, remote, local, chunk);
                Msg ack = read(in);
                if (ack.cmd != OKAY || ack.arg0 != local || ack.arg1 != remote) {
                    problems.add("WRTE not acknowledged with OKAY(local, remote)");
                    return;
                }
            }
            send(out, CLSE, remote, local, new byte[0]);
            if (staleClose == StaleClose.DUPLICATE_IMMEDIATELY) {
                send(out, CLSE, remote, local, new byte[0]); // reaches the client before or after its own CLSE
            } else if (staleClose == StaleClose.AFTER_CLIENT_CLSE) {
                Msg reply = read(in);
                if (reply.cmd != CLSE || reply.arg0 != local || reply.arg1 != remote) {
                    problems.add("expected client CLSE(local, remote) after our CLSE");
                }
                send(out, CLSE, remote, local, new byte[0]);
            }
            prevRemote = remote;
            prevLocal = local;
        }
    }

    private String execute(String cmd) {
        if (cmd.equals("shell:pm list packages -d")) {
            return list(disabled);
        }
        if (cmd.equals("shell:pm list packages")) {
            return list(installed);
        }
        if (cmd.equals("shell:pm list packages -e")) {
            Set<String> enabled = new HashSet<>(installed);
            enabled.removeAll(disabled);
            return list(enabled);
        }
        String prefix = "shell:pm disable-user --user 0 ";
        if (cmd.startsWith(prefix)) {
            String pkg = cmd.substring(prefix.length());
            if (refuseDisable.contains(pkg)) {
                return "Exception occurred while executing:\r\njava.lang.IllegalArgumentException: Cannot disable " + pkg + "\r\n";
            }
            if (!installed.contains(pkg)) {
                return "Error: Unknown package: " + pkg + "\r\n";
            }
            disabled.add(pkg);
            return "Package " + pkg + " new state: disabled-user\r\n";
        }
        problems.add("unexpected command: " + cmd);
        return "";
    }

    private static String list(Set<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        synchronized (pkgs) {
            for (String p : new TreeSet<>(pkgs)) {
                sb.append("package:").append(p).append("\r\n"); // legacy shell runs in a pty: CRLF
            }
        }
        return sb.toString();
    }

    private byte[] newToken() {
        byte[] t = new byte[20];
        random.nextBytes(t);
        return t;
    }

    private static boolean verify(RSAPublicKey key, byte[] token, byte[] sig) throws GeneralSecurityException {
        Signature v = Signature.getInstance("NONEwithRSA");
        v.initVerify(key);
        v.update(KeyEncodingCheck.concat(AdbKey.SHA1_DIGEST_INFO, token));
        return v.verify(sig);
    }

    /** Parses "base64(blob) name\0" and checks n0inv and rr against n, as adbd's key parser relies on them. */
    private RSAPublicKey decodePayload(byte[] data) throws GeneralSecurityException {
        String text = new String(data, StandardCharsets.US_ASCII);
        if (!text.endsWith("\0") || text.indexOf(' ') < 0) {
            problems.add("public key payload not 'base64 name\\0'");
        }
        byte[] blob = Base64.getDecoder().decode(text.substring(0, text.indexOf(' ')));
        ByteBuffer b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        int words = b.getInt();
        long n0inv = b.getInt() & 0xffffffffL;
        byte[] nLe = new byte[words * 4];
        b.get(nLe);
        byte[] rrLe = new byte[words * 4];
        b.get(rrLe);
        int e = b.getInt();
        BigInteger n = new BigInteger(1, KeyEncodingCheck.reverse(nLe));
        BigInteger rr = new BigInteger(1, KeyEncodingCheck.reverse(rrLe));
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        if (words != 64 || blob.length != 524) {
            problems.add("blob size: words=" + words + " bytes=" + blob.length);
        }
        if (!BigInteger.valueOf(n0inv).multiply(n).mod(r32).equals(r32.subtract(BigInteger.ONE))) {
            problems.add("n0inv is not -1/n mod 2^32");
        }
        if (!rr.equals(BigInteger.ONE.shiftLeft(4096).mod(n))) {
            problems.add("rr is not 2^4096 mod n");
        }
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, BigInteger.valueOf(e)));
    }

    private Msg read(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg(b.getInt(), b.getInt(), b.getInt());
        int len = b.getInt();
        int sum = b.getInt();
        if (b.getInt() != ~m.cmd) {
            problems.add("bad magic");
        }
        m.data = new byte[len];
        in.readFully(m.data);
        int actual = 0;
        for (byte x : m.data) {
            actual += x & 0xff;
        }
        if (actual != sum) {
            problems.add("bad checksum");
        }
        return m;
    }

    private static void send(OutputStream out, int cmd, int a0, int a1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt(0).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    private static final class Msg {
        final int cmd, arg0, arg1;
        byte[] data;

        Msg(int cmd, int arg0, int arg1) {
            this.cmd = cmd;
            this.arg0 = arg0;
            this.arg1 = arg1;
        }
    }
}
