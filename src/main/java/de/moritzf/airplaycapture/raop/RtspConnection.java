package de.moritzf.airplaycapture.raop;

import de.moritzf.airplaycapture.Log;
import de.moritzf.airplaycapture.recording.RecordingManager;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One RTSP control connection (ANNOUNCE / SETUP / RECORD / SET_PARAMETER / ...). */
final class RtspConnection implements Runnable {

    private static final Pattern AIRPLAY_APP = Pattern.compile(
        "^AirPlay/[^\\s]+\\s+(.+?)(?:/[^\\s]+)?$"
    );

    private static final String PUBLIC_METHODS =
        "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, " +
        "SET_PARAMETER, POST, GET";

    private final RaopServer server;
    private final RecordingManager recorder;
    private final Socket socket;
    private final InetAddress clientAddress;
    private final InetAddress localAddress;

    private Sdp announced;
    private StreamSession session;
    private boolean loggedSender;

    RtspConnection(RaopServer server, RecordingManager recorder, Socket socket) throws IOException {
        this.server = server;
        this.recorder = recorder;
        this.socket = socket;
        this.socket.setTcpNoDelay(true);
        this.clientAddress = socket.getInetAddress();
        InetAddress local = socket.getLocalAddress();
        this.localAddress =
            local == null || local.isAnyLocalAddress() ? server.interfaceAddress() : local;
    }

    InetAddress clientAddress() {
        return clientAddress;
    }

    @Override
    public void run() {
        Log.info("rtsp", "connection from " + clientAddress.getHostAddress());
        recorder.onClientConnected(clientAddress.getHostAddress());
        try (
            InputStream rawIn = socket.getInputStream();
            OutputStream out = socket.getOutputStream()
        ) {
            BufferedInputStream in = new BufferedInputStream(rawIn);
            ByteArrayOutputStream capture = new ByteArrayOutputStream(1024);
            socket.setSoTimeout(8000);
            while (!socket.isClosed()) {
                Request request;
                try {
                    request = read(in, capture);
                } catch (SocketTimeoutException e) {
                    byte[] raw = capture.toByteArray();
                    Log.info(
                        "rtsp",
                        "no RTSP request from " +
                            clientAddress.getHostAddress() +
                            " within 8s (" +
                            raw.length +
                            " bytes)" +
                            (raw.length == 0 ? "" : ": " + hexPrefix(raw))
                    );
                    break;
                }
                if (request == null) {
                    break;
                }
                socket.setSoTimeout(0);
                capture.reset();
                Log.info("rtsp", request.method + " " + request.uri);
                if (!loggedSender) {
                    loggedSender = true;
                    String userAgent = request.headers.getOrDefault("User-Agent", "unknown");
                    String application = request.headers.get("X-Apple-Client-Application");
                    if (application == null || application.isBlank()) {
                        application = appFromUserAgent(userAgent);
                    }
                    server.reportSenderApp(application);
                    Log.info(
                        "rtsp",
                        "sender from " +
                            clientAddress.getHostAddress() +
                            ": " +
                            userAgent +
                            (application.isBlank() ? "" : " app=" + application)
                    );
                    Log.info("rtsp", "sender headers: " + request.headers);
                }
                Response response;
                try {
                    response = handle(request);
                } catch (Exception e) {
                    Log.error("rtsp", request.method + " failed", e);
                    response = new Response(500, "Internal Server Error");
                }
                write(out, request, response);
            }
        } catch (IOException e) {
            if (!socket.isClosed()) {
                Log.warn("rtsp", "connection error: " + e.getMessage());
            }
        } finally {
            close();
            Log.info("rtsp", "connection closed (" + clientAddress.getHostAddress() + ")");
        }
    }

    private void close() {
        StreamSession mine;
        synchronized (this) {
            mine = session;
            session = null;
        }
        server.endSession(mine);
        try {
            socket.close();
        } catch (IOException ignored) {
            // closed
        }
    }

    private Response handle(Request request) {
        Response response = new Response(200, "OK");
        response.headers.put("Server", "AirTunes/130.14");
        switch (request.method) {
            case "OPTIONS" -> response.headers.put("Public", PUBLIC_METHODS);
            case "ANNOUNCE" -> {
                announced = Sdp.parse(request.bodyAsString());
                recorder.onStreamConfigured(announced.alac());
                Log.info("rtsp", "ANNOUNCE: " + announced);
                response.headers.put("Audio-Jack-Status", "connected; type=analog");
            }
            case "SETUP" -> setup(request, response);
            case "RECORD" -> {
                if (session != null) {
                    Thread.ofVirtual()
                        .name("timing-requests")
                        .start(() -> {
                            for (int i = 0; i < 3; i++) {
                                session.sendTimingRequest();
                                try {
                                    Thread.sleep(250);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                        });
                }
                Log.info("rtsp", "RECORD");
            }
            case "PAUSE" -> Log.info("rtsp", "PAUSE");
            case "FLUSH" -> {
                Log.info("rtsp", "FLUSH");
                recorder.onFlush();
            }
            case "TEARDOWN" -> {
                Log.info("rtsp", "TEARDOWN");
                StreamSession mine;
                synchronized (this) {
                    mine = session;
                    session = null;
                }
                server.endSession(mine);
            }
            case "SET_PARAMETER" -> setParameter(request);
            case "GET_PARAMETER" -> {
                String volume = "volume: 0.000000\r\n";
                response.body = volume.getBytes(StandardCharsets.US_ASCII);
                response.headers.put("Content-Type", "text/parameters");
            }
            default -> Log.info("rtsp", "unhandled method " + request.method);
        }
        String challenge = request.headers.get("Apple-Challenge");
        if (challenge != null && !challenge.isBlank()) {
            try {
                response.headers.put(
                    "Apple-Response",
                    RaopCrypto.appleResponse(
                        decodeChallenge(challenge),
                        localAddress.getAddress(),
                        server.deviceId()
                    )
                );
            } catch (Exception e) {
                Log.warn("rtsp", "cannot answer Apple-Challenge: " + e.getMessage());
            }
        }
        return response;
    }

    private void setup(Request request, Response response) {
        if (announced == null) {
            response.code = 412;
            response.reason = "Precondition Failed";
            Log.warn("rtsp", "SETUP without ANNOUNCE");
            return;
        }
        String transport = request.headers.get("Transport");
        int controlPort = intParam(transport, "control_port=");
        int timingPort = intParam(transport, "timing_port=");
        if (controlPort == 0 || timingPort == 0) {
            response.code = 400;
            response.reason = "Bad Request";
            Log.warn("rtsp", "SETUP without control_port/timing_port: " + transport);
            return;
        }
        try {
            StreamSession created;
            synchronized (this) {
                if (session != null) {
                    server.endSession(session);
                }
                created = server.startSession(
                    clientAddress,
                    localAddress,
                    announced,
                    controlPort,
                    timingPort
                );
                session = created;
            }
            response.headers.put(
                "Transport",
                String.format(
                    Locale.ROOT,
                    "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=%d;timing_port=%d;server_port=%d",
                    created.controlPort(),
                    created.timingPort(),
                    created.audioPort()
                )
            );
            response.headers.put("Session", "1");
            Log.info(
                "rtsp",
                "SETUP from " +
                    clientAddress.getHostAddress() +
                    " (client control=" +
                    controlPort +
                    ", timing=" +
                    timingPort +
                    ")"
            );
        } catch (RaopServer.BusyException e) {
            response.code = 453;
            response.reason = "Not Enough Bandwidth";
            Log.warn("rtsp", e.getMessage());
        } catch (IOException e) {
            response.code = 500;
            response.reason = "Internal Server Error";
            Log.error("rtsp", "cannot open stream: " + e.getMessage());
        }
    }

    private void setParameter(Request request) {
        String contentType = request.headers
            .getOrDefault("Content-Type", "")
            .toLowerCase(Locale.ROOT);
        byte[] body = request.body;
        if (body == null || body.length == 0) {
            return;
        }
        switch (contentType) {
            case "application/x-dmap-tagged" -> recorder.onMetadata(Dmap.track(Dmap.parse(body)));
            case "image/jpeg", "image/png" -> recorder.onArtwork(body, contentType);
            case "image/none" -> recorder.onArtworkNone();
            case "text/parameters" -> handleParameters(new String(body, StandardCharsets.UTF_8));
            default -> Log.info(
                "rtsp",
                "SET_PARAMETER " + contentType + " (" + body.length + " bytes)"
            );
        }
    }

    private void handleParameters(String body) {
        for (String line : body.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (key.equals("progress")) {
                String[] parts = value.split("/");
                if (parts.length != 3) {
                    continue;
                }
                try {
                    long start = Long.parseLong(parts[0].trim());
                    long current = Long.parseLong(parts[1].trim());
                    long end = Long.parseLong(parts[2].trim());
                    long position = Math.max(0, (current - start) / 44100);
                    long duration = Math.max(0, (end - start) / 44100);
                    recorder.onProgress(position, duration);
                } catch (NumberFormatException ignored) {
                    // malformed progress line
                }
            }
        }
    }

    private static int intParam(String transport, String name) {
        if (transport == null) {
            return 0;
        }
        int index = transport.indexOf(name);
        if (index < 0) {
            return 0;
        }
        int position = index + name.length();
        int end = position;
        while (end < transport.length() && Character.isDigit(transport.charAt(end))) {
            end++;
        }
        try {
            return Integer.parseInt(transport.substring(position, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final class Request {

        final String method;
        final String uri;
        final Map<String, String> headers;
        final byte[] body;

        Request(String method, String uri, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.uri = uri;
            this.headers = headers;
            this.body = body;
        }

        String bodyAsString() {
            return body == null ? "" : new String(body, StandardCharsets.UTF_8);
        }
    }

    private static final class Response {

        int code;
        String reason;
        final Map<String, String> headers = new LinkedHashMap<>();
        byte[] body;

        Response(int code, String reason) {
            this.code = code;
            this.reason = reason;
            this.headers.put("Audio-Jack-Status", "connected; type=analog");
        }
    }

    private static Request read(BufferedInputStream in, ByteArrayOutputStream headerBytes)
        throws IOException {
        String requestLine = readLine(in, headerBytes);
        if (requestLine == null || requestLine.isEmpty()) {
            if (headerBytes.size() > 0) {
                Log.warn(
                    "rtsp",
                    "incomplete request (" +
                        headerBytes.size() +
                        " bytes): " +
                        hexPrefix(headerBytes.toByteArray())
                );
            }
            return null;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 3) {
            Log.warn("rtsp", "malformed request: " + requestLine);
            return null;
        }
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String lastKey = null;
        String line;
        while ((line = readLine(in, headerBytes)) != null && !line.isEmpty()) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && lastKey != null) {
                headers.put(lastKey, headers.get(lastKey) + " " + line.trim());
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            lastKey = line.substring(0, colon).trim();
            headers.put(lastKey, line.substring(colon + 1).trim());
        }
        if (line == null) {
            return null; // connection closed before the header block ended
        }
        byte[] body = null;
        String contentLength = headers.get("Content-Length");
        if (contentLength != null) {
            int length;
            try {
                length = Integer.parseInt(contentLength.trim());
            } catch (NumberFormatException e) {
                length = 0;
            }
            if (length > 0 && length < 8 * 1024 * 1024) {
                body = in.readNBytes(length);
            }
        }
        return new Request(parts[0], parts[1], headers, body);
    }

    static String appFromUserAgent(String userAgent) {
        if (userAgent == null) {
            return "";
        }
        Matcher matcher = AIRPLAY_APP.matcher(userAgent.trim());
        return matcher.matches() ? matcher.group(1).trim() : "";
    }

    private static String hexPrefix(byte[] data) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(data.length, 64); i++) {
            text.append(String.format("%02X ", data[i]));
        }
        return text.toString().trim();
    }

    private static String readLine(InputStream in, ByteArrayOutputStream capture)
        throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int b;
        boolean any = false;
        while ((b = in.read()) != -1) {
            any = true;
            capture.write(b);
            if (b == '\n') {
                break;
            }
            line.write(b);
        }
        if (!any) {
            return null;
        }
        String value = line.toString(StandardCharsets.UTF_8);
        if (value.endsWith("\r")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static void write(OutputStream out, Request request, Response response)
        throws IOException {
        StringBuilder header = new StringBuilder();
        header
            .append("RTSP/1.0 ")
            .append(response.code)
            .append(' ')
            .append(response.reason)
            .append("\r\n");
        String cseq = request.headers.get("CSeq");
        if (cseq != null) {
            header.append("CSeq: ").append(cseq).append("\r\n");
        }
        for (Map.Entry<String, String> entry : response.headers.entrySet()) {
            header.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        byte[] body = response.body;
        if (body == null) {
            body = new byte[0];
        }
        header.append("Content-Length: ").append(body.length).append("\r\n\r\n");
        out.write(header.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    static byte[] decodeChallenge(String header) {
        try {
            return Base64.getDecoder().decode(header.trim());
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }
}
