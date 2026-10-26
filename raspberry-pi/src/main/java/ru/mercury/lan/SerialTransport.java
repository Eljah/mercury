package ru.mercury.lan;

import com.fazecast.jSerialComm.SerialPort;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** The GPIO UART connects to a CAN physical transceiver; no SocketCAN or MCP2515 is involved. */
public final class SerialTransport implements MeterTransport {
    private final Config config;
    private SerialPort port;

    public SerialTransport(Config config) { this.config = config; }

    private void ensureOpen() throws IOException {
        if (port != null && port.isOpen()) return;
        port = SerialPort.getCommPort(config.serialDevice());
        port.setComPortParameters(config.baud(), 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        if (!port.openPort()) throw new IOException("Cannot open " + config.serialDevice()
                + "; check UART configuration, dialout permissions, and exclusive use");
    }

    @Override public synchronized long[] readTariffs() throws IOException {
        try {
            ensureOpen();
            // No transaction is outstanding: discard late/stale traffic before sending a new request.
            if (!port.flushIOBuffers()) throw new IOException("Cannot flush serial buffers");
            byte[] request = MercuryProtocol.request(config.address());
            if (port.writeBytes(request, request.length) != request.length) throw new IOException("Incomplete serial write");
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.timeoutMs());
            MercuryProtocol.Scanner scanner = new MercuryProtocol.Scanner(config.address());
            byte[] chunk = new byte[128];
            int totalReceived = 0;
            while (System.nanoTime() < deadline) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Serial polling interrupted");
                int count = port.readBytes(chunk, chunk.length);
                if (count < 0) throw new IOException("Serial read failed");
                totalReceived += count;
                if (totalReceived > 4096) throw new IOException("Excess serial traffic; expected an exclusive meter bus");
                for (int i = 0; i < count; i++) {
                    Optional<long[]> decoded = scanner.accept(chunk[i]);
                    if (decoded.isPresent()) return decoded.get();
                }
                if (count == 0) {
                    try { Thread.sleep(2); }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Serial polling interrupted", e);
                    }
                }
            }
            throw new IOException("No valid tariff reply within " + config.timeoutMs()
                    + " ms (received " + totalReceived + " bytes, possibly including TX echo)");
        } catch (IOException | RuntimeException failure) {
            close(); // Retry opening on the next scheduled poll, not in a tight retry loop.
            throw failure;
        }
    }

    @Override public synchronized void close() {
        if (port != null) { port.closePort(); port = null; }
    }
}
