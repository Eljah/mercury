package ru.mercury.lan;

import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ReadingsServerTest {
    private static Config config(boolean demo) {
        return new Config(651401, "/dev/serial0", 9600, 1500, 30, "127.0.0.1", 8080, demo);
    }

    @Test void noSampleIsNeverReportedAsZeroAndFailuresRetainPreviousSample() {
        StateStore state = new StateStore(config(false));
        assertFalse(state.reply().ok());
        assertTrue(state.reply().json().contains("\"tariffs_kwh\":null"));
        assertTrue(state.reply().json().contains("\"total_kwh\":null"));
        assertTrue(state.reply().json().contains("\"demo\":false"));
        long[] tariffs = {62142, 20834, 11111, 22222};
        state.success(tariffs);
        tariffs[0] = 0; // Verify a caller cannot modify the published snapshot.
        assertTrue(state.reply().ok());
        assertTrue(state.reply().json().contains("\"tariffs_kwh\":[621.42,208.34,111.11,222.22]"));
        assertTrue(state.reply().json().contains("\"total_kwh\":1163.09"));
        state.failure("poll_failed");
        assertFalse(state.reply().ok());
        assertTrue(state.reply().json().contains("\"has_reading\":true"));
        assertTrue(state.reply().json().contains("\"total_kwh\":1163.09"));
    }

    @Test void httpReportsHealthAndRejectsMutationAndUnexpectedPaths() throws Exception {
        StateStore state = new StateStore(config(true));
        try (ReadingsServer server = new ReadingsServer(new InetSocketAddress("127.0.0.1", 0), state)) {
            server.start();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            String base = "http://127.0.0.1:" + server.port();
            assertEquals(503, get(client, base + "/api/v1/readings").statusCode());
            state.success(new DemoTransport().readTariffs());
            var response = get(client, base + "/api/v1/readings");
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"demo\":true"));
            assertTrue(response.body().contains("\"total_kwh\":2023.57"));
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(200, get(client, base + "/health").statusCode());
            HttpRequest post = HttpRequest.newBuilder(URI.create(base + "/api/v1/readings"))
                    .timeout(Duration.ofSeconds(2)).POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(405, client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(404, get(client, base + "/api/v1/readings/not-an-endpoint").statusCode());
            state.failure("poll_failed");
            assertEquals(503, get(client, base + "/health").statusCode());
        }
    }

    @Test void syntheticTransportIsDeterministicAndClearlyFlagged() {
        DemoTransport demo = new DemoTransport();
        assertArrayEquals(demo.readTariffs(), demo.readTariffs());
        StateStore state = new StateStore(config(true));
        state.success(demo.readTariffs());
        assertTrue(state.reply().json().contains("\"demo\":true"));
    }

    private static HttpResponse<String> get(HttpClient client, String uri) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
