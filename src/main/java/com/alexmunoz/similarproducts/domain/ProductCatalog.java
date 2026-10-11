package com.alexmunoz.similarproducts.domain;

import java.util.List;
import java.util.Optional;

/**
 * Source of product information.
 */
public interface ProductCatalog {

    /**
     * Returns the ids of the products similar to the given one, ordered by similarity.
     *
     * @throws ProductNotFoundException if the given product does not exist
     * @throws ProductCatalogException  if the catalog answers without the ids
     */
    List<String> findSimilarIds(String productId);

    /**
     * Returns the detail of the given product, or empty if it does not exist.
     *
     * @throws ProductCatalogException if the catalog answers without the detail
     */
    Optional<ProductDetail> findById(String productId);
}
