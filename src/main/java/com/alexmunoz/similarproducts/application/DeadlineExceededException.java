package com.alexmunoz.similarproducts.application;

/**
 * The budget of the request ran out before the work was done.
 */
public class DeadlineExceededException extends RuntimeException {

    public DeadlineExceededException(String message) {
        super(message);
    }
}
