package com.alexmunoz.similarproducts.domain;

public class ProductNotFoundException extends RuntimeException {

    private final String productId;

    public ProductNotFoundException(String productId) {
        super("Product " + productId + " not found");
        this.productId = productId;
    }

    public String getProductId() {
        return productId;
    }
}
