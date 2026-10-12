package com.alexmunoz.similarproducts.application;

/**
 * A call to the product catalog was not started because too many calls were already in
 * progress. It is a local limit, not a failure of the catalog.
 */
public class CatalogCallRejectedException extends RuntimeException {

    public CatalogCallRejectedException(String message) {
        super(message);
    }
}
