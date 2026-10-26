package ru.mercury.lan;

/** Deliberately synthetic, deterministic readings; this class never touches serial hardware. */
public final class DemoTransport implements MeterTransport {
    @Override public long[] readTariffs() { return new long[] {123456, 78901, 0, 0}; }
    @Override public void close() { }
}
