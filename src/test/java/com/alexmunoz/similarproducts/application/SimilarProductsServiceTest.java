package com.alexmunoz.similarproducts.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alexmunoz.similarproducts.domain.ProductDetail;
import com.alexmunoz.similarproducts.domain.ProductNotFoundException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SimilarProductsServiceTest {

    private static final ProductDetail SHIRT = product("1", "Shirt");
    private static final ProductDetail DRESS = product("2", "Dress");
    private static final ProductDetail BLAZER = product("3", "Blazer");

    private final FakeProductCatalog catalog = new FakeProductCatalog();

    @Test
    void returnsTheDetailOfEachSimilarProductInSimilarityOrder() {
        catalog.similarIds("10", "3", "1", "2");
        catalog.product(SHIRT);
        catalog.product(DRESS);
        catalog.product(BLAZER);

        assertThat(service().getSimilarProducts("10")).containsExactly(BLAZER, SHIRT, DRESS);
    }

    @Test
    void leavesOutSimilarProductsThatDoNotExist() {
        catalog.similarIds("10", "1", "404", "2");
        catalog.product(SHIRT);
        catalog.product(DRESS);

        assertThat(service().getSimilarProducts("10")).containsExactly(SHIRT, DRESS);
    }

    @Test
    void returnsAnEmptyListWhenThereAreNoSimilarProducts() {
        catalog.similarIds("10");

        assertThat(service().getSimilarProducts("10")).isEmpty();
    }

    @Test
    void returnsAnEmptyListWhenNoSimilarProductExists() {
        catalog.similarIds("10", "404", "405");

        assertThat(service().getSimilarProducts("10")).isEmpty();
    }

    @Test
    void doesNotRepeatProductsWhenSimilarIdsContainDuplicates() {
        catalog.similarIds("10", "1", "2", "1");
        catalog.product(SHIRT);
        catalog.product(DRESS);

        assertThat(service().getSimilarProducts("10")).containsExactly(SHIRT, DRESS);
    }

    @Test
    void failsWhenTheProductDoesNotExist() {
        assertThatThrownBy(() -> service().getSimilarProducts("404"))
                .isInstanceOf(ProductNotFoundException.class);
    }

    private SimilarProductsService service() {
        return new SimilarProductsService(catalog);
    }

    private static ProductDetail product(String id, String name) {
        return new ProductDetail(id, name, new BigDecimal("9.99"), true);
    }
}
