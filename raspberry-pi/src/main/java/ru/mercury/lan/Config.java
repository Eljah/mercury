package ru.mercury.lan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record Config(long address, String serialDevice, int baud, int timeoutMs,
                     int pollSeconds, String bind, int port, boolean demo) {
    public Config {
        if (address < 1 || address > 0xffff_ffffL) throw new IllegalArgumentException("meter.address must be 1..4294967295");
        if (serialDevice == null || serialDevice.isBlank()) throw new IllegalArgumentException("serial.device is empty");
        if (baud != 9600) throw new IllegalArgumentException("This hardware project is qualified for 9600 baud only");
        if (timeoutMs < 100 || timeoutMs > 10000) throw new IllegalArgumentException("serial.timeout.ms must be 100..10000");
        if (pollSeconds < 1 || pollSeconds > 86400) throw new IllegalArgumentException("poll.seconds must be 1..86400");
        if (bind == null || bind.isBlank()) throw new IllegalArgumentException("http.bind is empty");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("http.port must be 1..65535");
    }

    public static Config load(String[] args) throws IOException {
        Properties p = new Properties();
        // Load explicitly named files first; options then override file values in any argument order.
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--config")) {
                String file = argument(args, ++i, "--config");
                try (InputStream in = Files.newInputStream(Path.of(file))) { p.load(in); }
            }
        }
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> i++;
                case "--demo" -> p.setProperty("demo", "true");
                case "--address" -> p.setProperty("meter.address", argument(args, ++i, "--address"));
                case "--device" -> p.setProperty("serial.device", argument(args, ++i, "--device"));
                case "--port" -> p.setProperty("http.port", argument(args, ++i, "--port"));
                case "--bind" -> p.setProperty("http.bind", argument(args, ++i, "--bind"));
                case "--poll-seconds" -> p.setProperty("poll.seconds", argument(args, ++i, "--poll-seconds"));
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
        String demo = p.getProperty("demo", "false").trim();
        if (!demo.equals("true") && !demo.equals("false")) throw new IllegalArgumentException("demo must be true or false");
        return new Config(Long.parseLong(p.getProperty("meter.address", "651401").trim()),
                p.getProperty("serial.device", "/dev/serial0").trim(),
                Integer.parseInt(p.getProperty("serial.baud", "9600").trim()),
                Integer.parseInt(p.getProperty("serial.timeout.ms", "1500").trim()),
                Integer.parseInt(p.getProperty("poll.seconds", "30").trim()),
                p.getProperty("http.bind", "0.0.0.0").trim(),
                Integer.parseInt(p.getProperty("http.port", "8080").trim()),
                Boolean.parseBoolean(demo));
    }

    private static String argument(String[] args, int index, String option) {
        if (index >= args.length) throw new IllegalArgumentException("Missing value after " + option);
        return args[index];
    }
}
