package com.alexmunoz.similarproducts.infrastructure.existingapis;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alexmunoz.similarproducts.domain.ProductCatalogException;
import com.alexmunoz.similarproducts.domain.ProductDetail;
import com.alexmunoz.similarproducts.domain.ProductNotFoundException;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * The adapter against WireMock playing the existing APIs, without a Spring context.
 */
class ExistingApisProductCatalogTest {

    @RegisterExtension
    static final WireMockExtension existingApis = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final ExistingApisProductCatalog catalog = new ExistingApisProductCatalog(client());

    @Test
    void returnsTheSimilarIds() {
        existingApis.stubFor(get("/product/1/similarids").willReturn(okJson("[2,3,4]")));

        assertThat(catalog.findSimilarIds("1")).containsExactly("2", "3", "4");
    }

    @Test
    void reportsAProductThatDoesNotExist() {
        existingApis.stubFor(get("/product/7/similarids").willReturn(notFound()));

        assertThatThrownBy(() -> catalog.findSimilarIds("7"))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void rejectsSimilarIdsWithoutBody() {
        existingApis.stubFor(get("/product/1/similarids").willReturn(ok()));

        assertThatThrownBy(() -> catalog.findSimilarIds("1"))
                .isInstanceOf(ProductCatalogException.class);
    }

    @Test
    void returnsTheDetailOfAProduct() {
        existingApis.stubFor(get("/product/1").willReturn(okJson("""
                {"id":"1","name":"Shirt","price":9.99,"availability":true}""")));

        assertThat(catalog.findById("1"))
                .contains(new ProductDetail("1", "Shirt", new BigDecimal("9.99"), true));
    }

    @Test
    void returnsEmptyForAProductThatDoesNotExist() {
        existingApis.stubFor(get("/product/5").willReturn(notFound()));

        assertThat(catalog.findById("5")).isEmpty();
    }

    @Test
    void rejectsADetailWithoutBody() {
        existingApis.stubFor(get("/product/1").willReturn(ok()));

        assertThatThrownBy(() -> catalog.findById("1"))
                .isInstanceOf(ProductCatalogException.class);
    }

    private static ExistingProductsApi client() {
        RestClient restClient = RestClient.create(existingApis.baseUrl());
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(ExistingProductsApi.class);
    }
}
