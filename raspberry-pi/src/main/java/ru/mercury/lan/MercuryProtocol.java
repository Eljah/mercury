package ru.mercury.lan;

import java.util.Arrays;
import java.util.Optional;

/** Read-only subset of the four-byte-address Mercury 200 protocol. */
public final class MercuryProtocol {
    public static final int READ_TARIFFS = 0x27;
    public static final int RESPONSE_SIZE = 23;
    private MercuryProtocol() { }

    public static byte[] request(long address) {
        if (address < 0 || address > 0xffff_ffffL) throw new IllegalArgumentException("Address out of range");
        byte[] data = new byte[7];
        data[0] = (byte) (address >>> 24);
        data[1] = (byte) (address >>> 16);
        data[2] = (byte) (address >>> 8);
        data[3] = (byte) address;
        data[4] = READ_TARIFFS;
        appendCrc(data);
        return data;
    }

    /** MODBUS polynomial; the protocol uses low CRC byte followed by high CRC byte. */
    public static int crc16(byte[] data, int length) {
        if (length < 0 || length > data.length) throw new IllegalArgumentException("Bad CRC length");
        int crc = 0xffff;
        for (int i = 0; i < length; i++) {
            crc ^= data[i] & 0xff;
            for (int bit = 0; bit < 8; bit++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xa001 : crc >>> 1;
        }
        return crc;
    }

    static void appendCrc(byte[] frame) {
        int crc = crc16(frame, frame.length - 2);
        frame[frame.length - 2] = (byte) crc;
        frame[frame.length - 1] = (byte) (crc >>> 8);
    }

    /** Result uses integer hundredths of a kWh to preserve all meter digits. */
    public static long[] decode(long address, byte[] frame) {
        if (frame.length != RESPONSE_SIZE) throw new IllegalArgumentException("Unexpected response length");
        long receivedAddress = ((frame[0] & 0xffL) << 24) | ((frame[1] & 0xffL) << 16)
                | ((frame[2] & 0xffL) << 8) | (frame[3] & 0xffL);
        if (receivedAddress != address) throw new IllegalArgumentException("Address mismatch");
        if ((frame[4] & 0xff) != READ_TARIFFS) throw new IllegalArgumentException("Command mismatch");
        int receivedCrc = (frame[21] & 0xff) | ((frame[22] & 0xff) << 8);
        if (crc16(frame, 21) != receivedCrc) throw new IllegalArgumentException("CRC mismatch");
        long[] tariffs = new long[4];
        for (int tariff = 0; tariff < 4; tariff++) {
            long value = 0;
            for (int i = 0; i < 4; i++) {
                int valueByte = frame[5 + tariff * 4 + i] & 0xff;
                int high = valueByte >>> 4;
                int low = valueByte & 15;
                if (high > 9 || low > 9) throw new IllegalArgumentException("Invalid BCD digit");
                value = value * 100 + high * 10L + low;
            }
            tariffs[tariff] = value;
        }
        return tariffs;
    }

    /** Fixed memory sliding scanner tolerates a local TX echo, noise, partial reads, bad frames. */
    public static final class Scanner {
        private final long address;
        private final byte[] window = new byte[RESPONSE_SIZE];
        private int size;

        public Scanner(long address) { this.address = address; }

        public Optional<long[]> accept(byte value) {
            if (size == window.length) {
                System.arraycopy(window, 1, window, 0, window.length - 1);
                size--;
            }
            window[size++] = value;
            if (size != window.length) return Optional.empty();
            try { return Optional.of(decode(address, window)); }
            catch (IllegalArgumentException rejected) { return Optional.empty(); }
        }

        public void reset() { size = 0; Arrays.fill(window, (byte) 0); }
    }
}
