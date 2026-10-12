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
     * @throws ProductNotFoundException        if the given product does not exist
     * @throws ProductCatalogTimeoutException  if the catalog does not answer completely in time
     * @throws ProductCatalogException         if the catalog does not answer properly
     */
    List<String> findSimilarIds(String productId);

    /**
     * Returns the detail of the given product, or empty if it does not exist.
     *
     * @throws ProductCatalogTimeoutException  if the catalog does not answer completely in time
     * @throws ProductCatalogException         if the catalog does not answer properly
     */
    Optional<ProductDetail> findById(String productId);
}
