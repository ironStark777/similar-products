package com.alexmunoz.similarproducts.infrastructure.rest;

import com.alexmunoz.similarproducts.application.SimilarProductsService;
import com.alexmunoz.similarproducts.domain.ProductDetail;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agreed contract: similarProducts.yaml.
 */
@RestController
@RequestMapping("/product")
class SimilarProductsController {

    private final SimilarProductsService similarProductsService;

    SimilarProductsController(SimilarProductsService similarProductsService) {
        this.similarProductsService = similarProductsService;
    }

    @GetMapping("/{productId}/similar")
    List<ProductDetail> getSimilarProducts(@PathVariable("productId") String productId) {
        return similarProductsService.getSimilarProducts(productId);
    }
}
