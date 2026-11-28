package com.changyu.ochat;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Loopback HTTP fixture shared by the media protocol tests. */
final class MediaFixtureServer implements Closeable {
    interface Handler { void serve(Exchange exchange) throws Exception; }
    final Map<String,Handler> routes = new ConcurrentHashMap<>();
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final ServerSocket listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
    private final Thread worker;
    private volatile Throwable failure;

    MediaFixtureServer() throws IOException {
        worker = new Thread(() -> {
            while (!listener.isClosed()) {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    Exchange e = new Exchange(socket);
                    requests.add(e.method + " " + e.path);
                    Handler handler = routes.get(e.path.split("\\?",2)[0]);
                    if (handler == null) e.respond(404, new byte[0]);
                    else handler.serve(e);
                } catch (Exception ex) {
                    if (!listener.isClosed()) failure = ex;
                }
            }
        }, "test-media-server");
        worker.setDaemon(true);
        worker.start();
    }

    String base() { return "http://127.0.0.1:" + listener.getLocalPort(); }
    void createContext(String path, Handler handler) { routes.put(path, handler); }

    @Override public void close() throws IOException {
        listener.close();
        if (failure != null) throw new AssertionError(failure);
    }

    static final class Exchange {
        final Map<String,String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final String path, method;
        final byte[] body;
        private final OutputStream out;
        Exchange(Socket socket) throws IOException {
            out = socket.getOutputStream();
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            String[] start = line(input).split(" ");
            method = start[0]; path = start[1];
            for (String line; !(line = line(input)).isEmpty();) {
                int colon = line.indexOf(':');
                headers.put(line.substring(0,colon),line.substring(colon+1).trim());
            }
            int n = Integer.parseInt(headers.getOrDefault("Content-Length","0"));
            if (n < 0 || n > 65536) throw new IOException("Invalid fixture request size");
            body = new byte[n];
            new DataInputStream(input).readFully(body);
        }
        private static String line(InputStream in) throws IOException {
            StringBuilder value = new StringBuilder();
            int ch;
            while ((ch = in.read()) != '\n') {
                if (ch < 0 || value.length() > 8192) throw new EOFException();
                if (ch != '\r') value.append((char)ch);
            }
            return value.toString();
        }
        void respond(int status, byte[] bytes) throws IOException {
            out.write(("HTTP/1.1 " + status + " Fixture\r\nConnection: close\r\nContent-Length: " +
                    bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
        }
    }
}
