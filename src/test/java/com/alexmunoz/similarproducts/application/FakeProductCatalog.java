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

    void product(ProductDetail product) {
        details.put(product.id(), () -> Optional.of(product));
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
