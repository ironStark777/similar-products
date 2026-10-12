package com.alexmunoz.similarproducts.application;

import java.time.Duration;

/**
 * The point by which the processing of a request must be done, measured on a
 * {@link NanoClock}. It bounds the work of the request, not the moment the client receives
 * the response: scheduling, serialization and transport come on top.
 */
public final class Deadline {

    private final NanoClock clock;
    private final long deadline;

    private Deadline(NanoClock clock, long deadline) {
        this.clock = clock;
        this.deadline = deadline;
    }

    public static Deadline after(Duration budget, NanoClock clock) {
        return new Deadline(clock, clock.nanoTime() + budget.toNanos());
    }

    /**
     * @return the time left, zero or negative once the deadline has passed
     */
    public long remainingNanos() {
        return deadline - clock.nanoTime();
    }

    public boolean hasPassed() {
        return remainingNanos() <= 0;
    }
}
