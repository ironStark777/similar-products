package com.alexmunoz.similarproducts.domain;

/**
 * The product catalog did not answer properly: an error status, a broken connection or an
 * unreadable response.
 */
public class ProductCatalogException extends RuntimeException {

    public ProductCatalogException(String message) {
        super(message);
    }

    public ProductCatalogException(String message, Throwable cause) {
        super(message, cause);
    }
}
