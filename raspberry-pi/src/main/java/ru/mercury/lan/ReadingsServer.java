package ru.mercury.lan;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ReadingsServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final StateStore state;

    public ReadingsServer(InetSocketAddress address, StateStore state) throws IOException {
        this.state = state;
        server = HttpServer.create(address, 16);
        executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                send(exchange, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!path.equals("/api/v1/readings") && !path.equals("/health")) {
                send(exchange, 404, "{\"error\":\"not_found\"}");
                return;
            }
            StateStore.Reply reply = state.reply();
            send(exchange, reply.ok() ? 200 : 503, reply.json());
        }
    }

    private static void send(HttpExchange exchange, int code, String json) throws IOException {
        byte[] body = (json + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(code, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
