package com.alexmunoz.similarproducts.infrastructure.existingapis;

import com.alexmunoz.similarproducts.domain.ProductCatalog;
import com.alexmunoz.similarproducts.domain.ProductCatalogException;
import com.alexmunoz.similarproducts.domain.ProductDetail;
import com.alexmunoz.similarproducts.domain.ProductNotFoundException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * {@link ProductCatalog} backed by the existing APIs. Only their 404 responses mean that a
 * product does not exist; a successful response without body is an error.
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
            return requireBody(existingProductsApi.getSimilarProductIds(productId),
                    "similar ids of product " + productId);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ProductNotFoundException(productId);
        }
    }

    @Override
    public Optional<ProductDetail> findById(String productId) {
        try {
            return Optional.of(requireBody(existingProductsApi.getProductDetail(productId),
                    "detail of product " + productId));
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        }
    }

    private static <T> T requireBody(T body, String what) {
        if (body == null) {
            throw new ProductCatalogException("Empty response for the " + what);
        }
        return body;
    }
}
