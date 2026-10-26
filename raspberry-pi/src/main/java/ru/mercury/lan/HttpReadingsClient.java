package ru.mercury.lan;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Same client works with the Arduino and Raspberry Pi HTTP endpoints. */
public final class HttpReadingsClient {
    private HttpReadingsClient() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: java -cp mercury-pi.jar ru.mercury.lan.HttpReadingsClient"
                    + " http://192.168.1.50/api/v1/readings [pollSeconds]");
            System.err.println("One argument reads once; pollSeconds repeats and writes JSON Lines to stdout.");
            System.exit(2);
        }
        URI uri = URI.create(args[0]);
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) throw new IllegalArgumentException("HTTP(S) URL required");
        int interval = args.length == 2 ? Integer.parseInt(args[1]) : 0;
        if (args.length == 2 && interval < 1) throw new IllegalArgumentException("pollSeconds must be positive");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
        do {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println(response.body().strip());
            if (response.statusCode() != 200) {
                System.err.println("HTTP " + response.statusCode() + ": sample is unavailable or latest meter poll failed; inspect ok/error/age_seconds.");
                if (interval == 0) System.exit(1);
            }
            if (interval > 0) Thread.sleep(interval * 1000L);
        } while (interval > 0);
    }
}
