package tv.openshelf.keeper.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Client for adbd's TCP transport (legacy protocol): CNXN/AUTH handshake, then one shell stream at a time. */
public final class AdbConnection implements Closeable {
    static final int A_CNXN = 0x4e584e43;
    static final int A_AUTH = 0x48545541;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;

    static final int A_VERSION = 0x01000000;
    static final int MAX_PAYLOAD = 4096;
    static final int AUTH_TOKEN = 1;
    static final int AUTH_SIGNATURE = 2;
    static final int AUTH_RSAPUBLICKEY = 3;

    static final int HEADER_SIZE = 24;
    static final int MAX_ACCEPTED_PAYLOAD = 1024 * 1024;
    static final int MAX_SHELL_OUTPUT = 4 * 1024 * 1024;
    private static final byte[] EMPTY = new byte[0];

    /** adbd rejected our signature and the offered key was not confirmed on the TV within the wait time. */
    public static final class AuthPendingException extends IOException {
        AuthPendingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final int readTimeoutMs;
    private int nextLocalId = 1;
    // adbd may send a second CLSE (or late WRTE/OKAY) for a stream after we already opened the next one.
    private final Set<Integer> closedIds = new HashSet<>();

    private AdbConnection(Socket socket, int readTimeoutMs) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
        this.readTimeoutMs = readTimeoutMs;
    }

    /**
     * Connects and authenticates. {@code onKeyOffered} runs when our public key was sent, i.e. when the
     * TV shows its "allow debugging" prompt; from then on adbd's reply is awaited for {@code authWaitMs}.
     */
    public static AdbConnection open(String host, int port, AdbKey key, String keyName,
                                     int connectTimeoutMs, int readTimeoutMs, int authWaitMs,
                                     Runnable onKeyOffered) throws IOException {
        Socket socket = new Socket();
        boolean ok = false;
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            socket.setTcpNoDelay(true);
            AdbConnection c = new AdbConnection(socket, readTimeoutMs);
            c.handshake(key, keyName, authWaitMs, onKeyOffered);
            ok = true;
            return c;
        } finally {
            if (!ok) {
                closeQuietly(socket);
            }
        }
    }

    private void handshake(AdbKey key, String keyName, int authWaitMs, Runnable onKeyOffered) throws IOException {
        send(A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.US_ASCII));
        boolean signed = false;
        boolean offered = false;
        while (true) {
            Message m;
            try {
                m = read();
            } catch (SocketTimeoutException | EOFException e) {
                if (offered) {
                    throw new AuthPendingException("key not confirmed on the TV", e);
                }
                throw e;
            }
            if (m.command == A_CNXN) {
                socket.setSoTimeout(readTimeoutMs);
                return;
            }
            if (m.command != A_AUTH || m.arg0 != AUTH_TOKEN) {
                throw new ProtocolException("unexpected " + name(m.command) + " during handshake");
            }
            if (m.data.length != AdbKey.TOKEN_SIZE) {
                throw new ProtocolException("auth token has " + m.data.length + " bytes");
            }
            if (!signed) {
                send(A_AUTH, AUTH_SIGNATURE, 0, key.sign(m.data));
                signed = true;
            } else if (!offered) {
                send(A_AUTH, AUTH_RSAPUBLICKEY, 0, key.publicKeyPayload(keyName));
                offered = true;
                socket.setSoTimeout(authWaitMs);
                onKeyOffered.run();
            } else {
                throw new AuthPendingException("key offered but adbd asked again", null);
            }
        }
    }

    /**
     * Runs {@code shell:<command>} and returns everything it wrote until adbd closed the stream.
     * A refused OPEN is an error, never an empty result: callers treat output as facts about the TV.
     */
    public String shell(String command) throws IOException {
        int localId = nextLocalId++;
        send(A_OPEN, localId, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean opened = false;
        while (true) {
            Message m = read();
            if (m.arg1 != localId) {
                if (closedIds.contains(m.arg1)) {
                    continue; // traffic for a finished stream: no reply needed, not an error
                }
                throw new ProtocolException(name(m.command) + " for stream " + m.arg1 + ", expected " + localId);
            }
            switch (m.command) {
                case A_OKAY:
                    opened = true;
                    break;
                case A_WRTE:
                    if (output.size() + m.data.length > MAX_SHELL_OUTPUT) {
                        throw new ProtocolException("shell output exceeds " + MAX_SHELL_OUTPUT + " bytes");
                    }
                    output.write(m.data, 0, m.data.length);
                    send(A_OKAY, localId, m.arg0, EMPTY);
                    break;
                case A_CLSE:
                    // arg0 == 0 (or CLSE before any OKAY) means adbd refused the OPEN.
                    closedIds.add(localId);
                    if (!opened || m.arg0 == 0) {
                        throw new ProtocolException("adbd refused shell:" + command);
                    }
                    send(A_CLSE, localId, m.arg0, EMPTY);
                    return new String(output.toByteArray(), StandardCharsets.UTF_8);
                default:
                    throw new ProtocolException("unexpected " + name(m.command) + " on shell stream");
            }
        }
    }

    @Override
    public void close() {
        closeQuietly(socket);
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(checksum(data)).putInt(~command);
        b.put(data);
        out.write(b.array());
        out.flush();
    }

    private Message read() throws IOException {
        byte[] header = new byte[HEADER_SIZE];
        in.readFully(header);
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = b.getInt();
        int arg0 = b.getInt();
        int arg1 = b.getInt();
        int length = b.getInt();
        b.getInt(); // data checksum; not checked (adbd >= 0x01000001 sends 0)
        int magic = b.getInt();
        if (magic != ~command) {
            throw new ProtocolException("bad message magic");
        }
        if (length < 0 || length > MAX_ACCEPTED_PAYLOAD) {
            throw new ProtocolException("payload length " + length);
        }
        byte[] data = new byte[length];
        in.readFully(data);
        return new Message(command, arg0, arg1, data);
    }

    static int checksum(byte[] data) {
        int sum = 0;
        for (byte d : data) {
            sum += d & 0xff;
        }
        return sum;
    }

    static String name(int command) {
        byte[] b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(command).array();
        return new String(b, StandardCharsets.US_ASCII);
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing left to release
        }
    }

    private static final class Message {
        final int command;
        final int arg0;
        final int arg1;
        final byte[] data;

        Message(int command, int arg0, int arg1, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }
}
