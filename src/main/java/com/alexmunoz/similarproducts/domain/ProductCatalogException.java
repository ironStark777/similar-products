package com.alexmunoz.similarproducts.domain;

/**
 * The product catalog did not answer properly.
 */
public class ProductCatalogException extends RuntimeException {

    public ProductCatalogException(String message) {
        super(message);
    }
}
