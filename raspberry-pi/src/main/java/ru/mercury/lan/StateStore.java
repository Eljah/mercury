package ru.mercury.lan;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** An immutable cached sample is published atomically; HTTP handlers never poll the meter. */
public final class StateStore {
    private record Sample(long[] tariffs, Instant capturedAt, long capturedNanos, boolean success, String error) { }
    private final Config config;
    private final AtomicReference<Sample> latest = new AtomicReference<>(new Sample(null, null, 0, false, "not_polled"));

    public record Reply(boolean ok, String json) { }

    public StateStore(Config config) { this.config = config; }

    public void success(long[] tariffs) {
        if (tariffs.length != 4) throw new IllegalArgumentException("Exactly four tariffs required");
        for (long tariff : tariffs) if (tariff < 0 || tariff > 99_999_999L) throw new IllegalArgumentException("Tariff out of BCD range");
        latest.set(new Sample(tariffs.clone(), Instant.now(), System.nanoTime(), true, null));
    }

    public void failure(String error) {
        latest.updateAndGet(old -> new Sample(old.tariffs, old.capturedAt, old.capturedNanos, false, error));
    }

    public Reply reply() {
        Sample sample = latest.get();
        boolean hasReading = sample.tariffs != null;
        long age = hasReading ? Math.max(0, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - sample.capturedNanos)) : 0;
        boolean stale = hasReading && age > config.pollSeconds() * 3L + config.timeoutMs() / 1000L;
        boolean ok = sample.success && hasReading && !stale;
        String error = stale && sample.success ? "stale_reading" : sample.error;
        StringBuilder json = new StringBuilder(512);
        json.append("{\"schema_version\":1,\"device\":\"mercury-200\",")
                .append("\"transport\":\"raspberry-pi-uart-can\",")
                .append("\"meter_address\":").append(config.address())
                .append(",\"demo\":").append(config.demo())
                .append(",\"ok\":").append(ok)
                .append(",\"error\":").append(error == null ? "null" : quote(error))
                .append(",\"has_reading\":").append(hasReading)
                .append(",\"age_seconds\":").append(hasReading ? Long.toString(age) : "null")
                .append(",\"captured_at\":").append(sample.capturedAt == null ? "null" : quote(sample.capturedAt.toString()))
                .append(",\"tariffs_kwh\":").append(hasReading ? "[" : "null");
        long total = 0;
        if (hasReading) {
            for (int i = 0; i < 4; i++) {
                if (i != 0) json.append(',');
                json.append(kwh(sample.tariffs[i]));
                total += sample.tariffs[i];
            }
        }
        if (hasReading) json.append(']');
        json.append(",\"total_kwh\":").append(hasReading ? kwh(total) : "null").append('}');
        return new Reply(ok, json.toString());
    }

    static String kwh(long hundredths) {
        long fraction = hundredths % 100;
        return (hundredths / 100) + "." + (fraction < 10 ? "0" : "") + fraction;
    }

    static String quote(String text) {
        StringBuilder result = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 32) result.append(String.format("\\u%04x", (int) c));
                    else result.append(c);
                }
            }
        }
        return result.append('"').toString();
    }
}
