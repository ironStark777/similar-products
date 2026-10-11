package com.alexmunoz.similarproducts.application;

import com.alexmunoz.similarproducts.domain.ProductCatalog;
import com.alexmunoz.similarproducts.domain.ProductDetail;
import com.alexmunoz.similarproducts.domain.ProductNotFoundException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Builds the list of similar products for a given one. Similar products that do not
 * exist are left out.
 */
@Service
public class SimilarProductsService {

    private final ProductCatalog productCatalog;

    public SimilarProductsService(ProductCatalog productCatalog) {
        this.productCatalog = productCatalog;
    }

    /**
     * @throws ProductNotFoundException if the given product does not exist
     */
    public List<ProductDetail> getSimilarProducts(String productId) {
        return productCatalog.findSimilarIds(productId).stream()
                .distinct()
                .map(productCatalog::findById)
                .flatMap(Optional::stream)
                .toList();
    }
}
