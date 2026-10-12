package com.alexmunoz.similarproducts.application;

/**
 * Source of {@link System#nanoTime()}-like readings, so tests can control the passing of time.
 */
@FunctionalInterface
public interface NanoClock {

    long nanoTime();

    static NanoClock system() {
        return System::nanoTime;
    }
}
