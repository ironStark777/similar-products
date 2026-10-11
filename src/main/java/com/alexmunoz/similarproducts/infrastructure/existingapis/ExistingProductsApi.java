package com.alexmunoz.similarproducts.infrastructure.existingapis;

import com.alexmunoz.similarproducts.domain.ProductDetail;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * Existing APIs: existingApis.yaml.
 */
@HttpExchange("/product")
public interface ExistingProductsApi {

    @GetExchange("/{productId}/similarids")
    List<String> getSimilarProductIds(@PathVariable("productId") String productId);

    @GetExchange("/{productId}")
    ProductDetail getProductDetail(@PathVariable("productId") String productId);
}
