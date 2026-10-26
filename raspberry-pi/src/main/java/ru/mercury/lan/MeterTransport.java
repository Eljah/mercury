package ru.mercury.lan;

import java.io.IOException;

public interface MeterTransport extends AutoCloseable {
    long[] readTariffs() throws IOException;
    @Override void close();
}
