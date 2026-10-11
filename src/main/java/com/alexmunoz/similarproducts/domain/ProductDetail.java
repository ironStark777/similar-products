package com.alexmunoz.similarproducts.domain;

import java.math.BigDecimal;

/**
 * Product detail as defined by both the agreed contract and the existing APIs.
 */
public record ProductDetail(String id, String name, BigDecimal price, boolean availability) {
}
