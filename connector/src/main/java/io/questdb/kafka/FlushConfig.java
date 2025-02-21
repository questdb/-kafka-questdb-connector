package io.questdb.kafka;

import java.util.concurrent.TimeUnit;

final class FlushConfig {
    int autoFlushRows;
    long autoFlushNanos;

    void reset() {
        autoFlushRows = 75_000;
        autoFlushNanos = TimeUnit.SECONDS.toNanos(1);
    }
}
