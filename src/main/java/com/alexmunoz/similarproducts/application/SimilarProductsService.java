package com.alexmunoz.similarproducts.application;

import com.alexmunoz.similarproducts.domain.ProductCatalog;
import com.alexmunoz.similarproducts.domain.ProductDetail;
import com.alexmunoz.similarproducts.domain.ProductNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Builds the list of similar products for a given one.
 *
 * <p>The details of the similar products are looked up in parallel within a
 * {@link LookupScope}: if one lookup fails, the others are interrupted and the request fails
 * at once. Every call to the catalog goes through {@link CatalogCallLimiter}, and no call holds
 * a slot while it waits for another, so the limit cannot block itself. Similar products that
 * do not exist are left out.
 */
@Service
public class SimilarProductsService {

    private final ProductCatalog productCatalog;
    private final CatalogCallLimiter catalogCalls;

    public SimilarProductsService(ProductCatalog productCatalog, CatalogCallLimiter catalogCalls) {
        this.productCatalog = productCatalog;
        this.catalogCalls = catalogCalls;
    }

    /**
     * @throws ProductNotFoundException     if the given product does not exist
     * @throws CatalogCallRejectedException if too many calls to the catalog are in progress
     */
    public List<ProductDetail> getSimilarProducts(String productId) {
        List<String> similarIds = catalogCalls.call(() -> productCatalog.findSimilarIds(productId));

        try (LookupScope lookups = new LookupScope()) {
            List<Supplier<Optional<ProductDetail>>> details = similarIds.stream()
                    .distinct()
                    .map(similarId -> lookups.fork(() -> catalogCalls.call(() -> productCatalog.findById(similarId))))
                    .toList();
            lookups.join();
            return details.stream()
                    .map(Supplier::get)
                    .flatMap(Optional::stream)
                    .toList();
        }
    }
}
