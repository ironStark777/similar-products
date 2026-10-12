package com.alexmunoz.similarproducts.application;

import com.alexmunoz.similarproducts.domain.ProductCatalog;
import com.alexmunoz.similarproducts.domain.ProductDetail;
import com.alexmunoz.similarproducts.domain.ProductNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * In-memory catalog for tests. Unknown products do not exist.
 */
final class FakeProductCatalog implements ProductCatalog {

    private final Map<String, Supplier<List<String>>> similarIds = new ConcurrentHashMap<>();
    private final Map<String, Supplier<Optional<ProductDetail>>> details = new ConcurrentHashMap<>();

    void similarIds(String productId, String... ids) {
        List<String> result = List.of(ids);
        similarIds.put(productId, () -> result);
    }

    /**
     * Answers the similar ids of a product with the given lookup, to make it fail.
     */
    void similarIdsLookup(String productId, Supplier<List<String>> lookup) {
        similarIds.put(productId, lookup);
    }

    void product(ProductDetail product) {
        details.put(product.id(), () -> Optional.of(product));
    }

    /**
     * Answers the detail of a product with the given lookup, to make it wait or fail.
     */
    void detailLookup(String productId, Supplier<Optional<ProductDetail>> lookup) {
        details.put(productId, lookup);
    }

    @Override
    public List<String> findSimilarIds(String productId) {
        Supplier<List<String>> ids = similarIds.get(productId);
        if (ids == null) {
            throw new ProductNotFoundException(productId);
        }
        return ids.get();
    }

    @Override
    public Optional<ProductDetail> findById(String productId) {
        return details.getOrDefault(productId, Optional::empty).get();
    }
}
