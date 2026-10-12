package com.alexmunoz.similarproducts.domain;

/**
 * The product catalog did not answer completely in time.
 */
public class ProductCatalogTimeoutException extends ProductCatalogException {

    public ProductCatalogTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
