package com.alexmunoz.similarproducts.application;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Clock for tests: time only passes when the test says so.
 */
final class FakeNanoClock implements NanoClock {

    private final AtomicLong now = new AtomicLong();

    @Override
    public long nanoTime() {
        return now.get();
    }

    void advance(Duration duration) {
        now.addAndGet(duration.toNanos());
    }
}
