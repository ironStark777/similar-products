package com.alexmunoz.similarproducts.infrastructure.existingapis;

import com.alexmunoz.similarproducts.domain.ProductCatalog;
import com.alexmunoz.similarproducts.domain.ProductDetail;
import com.alexmunoz.similarproducts.domain.ProductNotFoundException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * {@link ProductCatalog} backed by the existing APIs. Their 404 responses are translated
 * into domain terms.
 */
@Component
class ExistingApisProductCatalog implements ProductCatalog {

    private final ExistingProductsApi existingProductsApi;

    ExistingApisProductCatalog(ExistingProductsApi existingProductsApi) {
        this.existingProductsApi = existingProductsApi;
    }

    @Override
    public List<String> findSimilarIds(String productId) {
        try {
            return existingProductsApi.getSimilarProductIds(productId);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ProductNotFoundException(productId);
        }
    }

    @Override
    public Optional<ProductDetail> findById(String productId) {
        try {
            return Optional.ofNullable(existingProductsApi.getProductDetail(productId));
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        }
    }
}
