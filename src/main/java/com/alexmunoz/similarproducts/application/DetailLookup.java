package com.alexmunoz.similarproducts.application;

import com.alexmunoz.similarproducts.domain.ProductDetail;

/**
 * Outcome of looking up the detail of one similar product.
 */
sealed interface DetailLookup {

    record Found(ProductDetail product) implements DetailLookup {
    }

    record NotFound() implements DetailLookup {
    }

    /**
     * The detail could not be retrieved, for an expected reason.
     *
     * @param failure what went wrong, kept for the error response and the log
     */
    record Unavailable(Reason reason, RuntimeException failure) implements DetailLookup {
    }

    /**
     * Why a detail is unavailable, from the most to the least severe: failures of the
     * existing APIs come before our own saturation.
     */
    enum Reason {
        /** The existing APIs did not answer properly. */
        ERROR,
        /** The existing APIs did not answer completely in time, or the request ran out of budget. */
        TIMEOUT,
        /** The call was rejected by our concurrency limit. */
        SATURATED
    }
}
