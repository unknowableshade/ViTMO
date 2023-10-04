import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Formatter;
import java.util.HashMap;
import java.util.Map;

class HttpServer {
    private final File root;
    private final String index;

    private HttpServer(File root, int port, String index) throws IOException {
        this.root = root;
        this.index= index;

        ServerSocket serverSocket = new ServerSocket(port);
        while (serverSocket.isBound()) {
            try {
                Socket socket = serverSocket.accept();
                process(socket);
                socket.close();
            } catch (IOException ignored) {
                // No operations.
            }
        }
    }

    private HttpServer(File root, int port) throws IOException {
        this(root, port, "index.html");
    }

    private void process(Socket socket) throws IOException {
        Request request = readRequest(socket);

        Response response = new Response();
        response.setHeader("Connection", "close");

        try {
            process(request, response);
        } catch (Exception ignored) {
            response.setStatusCode(502);
        }

        try {
            writeResponse(socket, response);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void writeResponse(Socket socket, Response response) throws IOException {
        try {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            result.write(("HTTP/1.1 " + response.getStatusCode() + " NA\r\n").getBytes());
            for (Map.Entry<String, String> entry : response.getHeaders().entrySet()) {
                result.write((entry.getKey() + ": " + entry.getValue() + "\r\n").getBytes());
            }
            result.write("\r\n".getBytes());
            if (response.getBody() != null) {
                result.write(response.getBody());
            }

            OutputStream outputStream = socket.getOutputStream();
            outputStream.write(result.toByteArray());
            outputStream.close();
        } finally {
            socket.close();
        }
    }

    private void process(Request request, Response response) throws IOException {
        if (!"GET".equals(request.getMethod())) {
            response.setStatusCode(405);
            return;
        }

        File file = new File(root, request.getUri());

        if (file.isDirectory()) {
            file = new File(file, index);
        }

        if (file.isFile()) {
            byte[] body = readFile(file);
            String etag = getEtagValue(body);

            response.setHeader("ETag", etag);

            if (request.getHeader("If-None-Match") != null
                    && request.getHeader("If-None-Match").equals(etag)) {
                response.setStatusCode(304);
            } else {
                response.setStatusCode(200);
                response.setBody(body);
                response.setHeader("Content-Length", Integer.toString(body.length));
                response.setHeader("Content-Type", getContentType(file));
            }
        } else {
            response.setStatusCode(404);
        }
    }

    private String getEtagValue(byte[] data) {
        try {
            final MessageDigest md5 = MessageDigest.getInstance("MD5");
            final Formatter formatter = new Formatter();

            for (final byte b : md5.digest(data))
                formatter.format("%02X", b);

            return formatter.toString();
        } catch (NoSuchAlgorithmException never) {
            throw new AssertionError(never);
        }
    }

    private String getContentType(File file) {
        String path = file.getAbsolutePath();
        if (path.endsWith(".html") || path.endsWith(".htm")) {
            return "text/html";
        }
        if (path.endsWith(".png")) {
            return "image/png";
        }
        throw new IllegalArgumentException();
    }

    private void silentClose(Closeable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // No operations.
        }
    }

    private byte[] readInputStream(InputStream inputStream, boolean breakOnCrLf) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        byte[] lastTwoBytes = new byte[2];
        int lastTwoBytesSize = 0;

        try {
            byte[] buffer = new byte[1024];
            while (true) {
                if (breakOnCrLf && lastTwoBytes[0] == 13 && lastTwoBytes[1] == 10) {
                    break;
                }

                int read = inputStream.read(buffer);
                if (breakOnCrLf) {
                    if (read == 1) {
                        if (lastTwoBytesSize > 1) {
                            lastTwoBytes[0] = lastTwoBytes[1];
                        }
                        lastTwoBytesSize = Math.max(0, lastTwoBytesSize - 1);
                        lastTwoBytes[lastTwoBytesSize++] = buffer[read - 1];
                    } else if (read >= 2) {
                        lastTwoBytesSize = 2;
                        lastTwoBytes[0] = buffer[read - 2];
                        lastTwoBytes[1] = buffer[read - 1];
                    }
                }

                if (read >= 0) {
                    bytes.write(buffer, 0, read);
                } else {
                    break;
                }
            }
        } finally {
            silentClose(bytes);
        }

        return bytes.toByteArray();
    }

    private byte[] readFile(File file) throws IOException {
        InputStream inputStream = Files.newInputStream(file.toPath());
        try {
            return readInputStream(inputStream, false);
        } finally {
            silentClose(inputStream);
        }
    }

    private Request readRequest(Socket socket) throws IOException {
        InputStream inputStream = socket.getInputStream();
        return new Request(new String(readInputStream(inputStream, true)));
    }

    private static final class Request {
        private final String method;
        private final String uri;
        private final Map<String, String> headers;

        private Request(String request) {
            String[] lines = request.split("[\r\n]+");
            if (lines.length < 1) {
                throw new IllegalArgumentException();
            }

            String[] firstLineTokens = lines[0].split("\\s+");
            method = firstLineTokens[0];
            try {
                uri = java.net.URLDecoder.decode(firstLineTokens[1], StandardCharsets.UTF_8.name());
            } catch (UnsupportedEncodingException never) {
                throw new AssertionError(never);
            }
            headers = new HashMap<>();

            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.contains(":")) {
                    int sep = line.indexOf(':');
                    headers.put(line.substring(0, sep), line.substring(sep + 1).trim());
                }
            }
        }

        private String getMethod() {
            return method;
        }

        private String getUri() {
            return uri;
        }

        @SuppressWarnings({"SameParameterValue", "unused"})
        private String getHeader(String name) {
            return headers.get(name);
        }
    }

    private static final class Response {
        private int statusCode;
        private final Map<String, String> headers = new HashMap<>();
        private byte[] body;

        private void setHeader(String name, String value) {
            headers.put(name, value);
        }

        private int getStatusCode() {
            return statusCode;
        }

        private void setStatusCode(int statusCode) {
            this.statusCode = statusCode;
            this.body = ("Error " + statusCode).getBytes();
        }

        private byte[] getBody() {
            return body;
        }

        private void setBody(byte[] body) {
            this.body = body;
        }

        private Map<String, String> getHeaders() {
            return Collections.unmodifiableMap(headers);
        }
    }

    public static void main(String[] args) throws IOException {
        new HttpServer(new File("static"), 8088);
    }
}
