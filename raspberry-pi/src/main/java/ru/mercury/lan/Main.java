package ru.mercury.lan;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Main {
    private Main() { }

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--help")) {
            System.out.println("""
                    Mercury 200 LAN reader (Java 17)
                    java -jar mercury-pi.jar [--config FILE] [--demo]
                        [--device /dev/serial0] [--address 651401]
                        [--bind 0.0.0.0] [--port 8080] [--poll-seconds 30]
                    Read-only HTTP: GET /api/v1/readings and GET /health
                    --demo uses deterministic synthetic data and never opens a serial port.
                    Configuration is loaded only when --config FILE is supplied.
                    """);
            return;
        }
        Config config = Config.load(args);
        System.setProperty("sun.net.httpserver.maxReqTime", "10");
        System.setProperty("sun.net.httpserver.maxRspTime", "10");
        StateStore state = new StateStore(config);
        MeterTransport transport = config.demo() ? new DemoTransport() : new SerialTransport(config);
        ReadingsServer server = new ReadingsServer(new InetSocketAddress(config.bind(), config.port()), state);
        ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            poller.shutdownNow();
            transport.close();
            stopped.countDown();
        }, "mercury-shutdown"));
        poller.scheduleWithFixedDelay(() -> {
            try {
                state.success(transport.readTariffs());
            } catch (IOException | RuntimeException | LinkageError failure) {
                state.failure("poll_failed");
                System.err.println(Instant.now() + " Meter poll failed: " + failure);
            }
        }, 0, config.pollSeconds(), TimeUnit.SECONDS);
        server.start();
        System.out.println("Mercury reader listening on " + config.bind() + ":" + server.port()
                + "; address=" + config.address() + "; demo=" + config.demo());
        if (config.demo()) System.out.println("DEMO: readings are SYNTHETIC; no meter or GPIO was accessed.");
        else System.out.println("UART " + config.serialDevice() + " at 9600 8N1; read-only command 0x27.");
        stopped.await();
    }
}
