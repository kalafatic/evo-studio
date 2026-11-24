package eu.kalafatic.dstudio.teip.server;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Shared Server-Sent-Events plumbing for the EVO-Studio API surface. */
public final class SseSupport {

    private SseSupport() {
    }

    /** Opens a chunked {@code text/event-stream} response; caller owns the exchange body. */
    public static void startSse(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, 0);
    }

    /** Writes one SSE event ({@code id}/{@code event}/{@code data}) and flushes. */
    public static void writeEvent(OutputStream out, String id, String type, String data)
            throws IOException {
        writeLine(out, "id: " + id);
        writeLine(out, "event: " + type);
        for (String line : data.split("\n", -1)) {
            writeLine(out, "data: " + line);
        }
        writeLine(out, "");
        out.flush();
    }

    /** Writes an SSE comment (heartbeat/keep-alive). */
    public static void writeComment(OutputStream out, String comment) throws IOException {
        writeLine(out, ": " + comment);
        writeLine(out, "");
        out.flush();
    }

    private static void writeLine(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
