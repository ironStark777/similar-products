package com.alexmunoz.similarproducts.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DeadlineTest {

    private final FakeNanoClock clock = new FakeNanoClock();

    @Test
    void countsDownTheBudgetOnTheClock() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(2), clock);

        clock.advance(Duration.ofMillis(1_500));

        assertThat(deadline.remainingNanos()).isEqualTo(Duration.ofMillis(500).toNanos());
        assertThat(deadline.hasPassed()).isFalse();
    }

    @Test
    void passesWhenTheBudgetIsSpent() {
        Deadline deadline = Deadline.after(Duration.ofSeconds(2), clock);

        clock.advance(Duration.ofSeconds(2));

        assertThat(deadline.remainingNanos()).isZero();
        assertThat(deadline.hasPassed()).isTrue();
    }
}
