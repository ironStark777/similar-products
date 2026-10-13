package com.alexmunoz.similarproducts.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.alexmunoz.similarproducts.application.SimilarProductsProperties.CatalogCalls;
import com.alexmunoz.similarproducts.application.SimilarProductsProperties.DetailCache;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SimilarProductsPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class)
            .withPropertyValues(
                    "similar-products.request-budget=2s",
                    "similar-products.catalog-calls.max-concurrent=100",
                    "similar-products.catalog-calls.permit-timeout=100ms",
                    "similar-products.detail-cache.found-ttl=30s",
                    "similar-products.detail-cache.not-found-ttl=5s",
                    "similar-products.detail-cache.maximum-size=10000");

    @Test
    void bindsTheRequestBudgetTheLimitOfTheCatalogCallsAndTheDetailCache() {
        contextRunner.run(context -> assertThat(context.getBean(SimilarProductsProperties.class))
                .isEqualTo(new SimilarProductsProperties(Duration.ofSeconds(2),
                        new CatalogCalls(100, Duration.ofMillis(100)),
                        new DetailCache(Duration.ofSeconds(30), Duration.ofSeconds(5), 10_000))));
    }

    @Test
    void allowsRejectingCallsWithoutWaiting() {
        contextRunner.withPropertyValues("similar-products.catalog-calls.permit-timeout=0ms")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "similar-products.request-budget=0ms",
            "similar-products.catalog-calls.max-concurrent=0",
            "similar-products.catalog-calls.permit-timeout=-1ms",
            "similar-products.detail-cache.found-ttl=-1s",
            "similar-products.detail-cache.not-found-ttl=-1s",
            "similar-products.detail-cache.maximum-size=0"})
    void stopsTheStartupWithAnInvalidValue(String invalidProperty) {
        contextRunner.withPropertyValues(invalidProperty)
                .run(context -> assertThat(context).getFailure().hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Test
    void stopsTheStartupWithoutTheSettings() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class)
                .run(context -> assertThat(context).getFailure().hasRootCauseInstanceOf(BindValidationException.class));
    }

    @EnableConfigurationProperties(SimilarProductsProperties.class)
    static class PropertiesConfig {
    }
}
