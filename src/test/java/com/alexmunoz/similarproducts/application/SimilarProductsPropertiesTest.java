package com.alexmunoz.similarproducts.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.alexmunoz.similarproducts.application.SimilarProductsProperties.CatalogCalls;
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
                    "similar-products.catalog-calls.max-concurrent=100",
                    "similar-products.catalog-calls.permit-timeout=100ms");

    @Test
    void bindsTheLimitOfTheCatalogCalls() {
        contextRunner.run(context -> assertThat(context.getBean(SimilarProductsProperties.class).catalogCalls())
                .isEqualTo(new CatalogCalls(100, Duration.ofMillis(100))));
    }

    @Test
    void allowsRejectingCallsWithoutWaiting() {
        contextRunner.withPropertyValues("similar-products.catalog-calls.permit-timeout=0ms")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "similar-products.catalog-calls.max-concurrent=0",
            "similar-products.catalog-calls.permit-timeout=-1ms"})
    void stopsTheStartupWithAnInvalidLimit(String invalidProperty) {
        contextRunner.withPropertyValues(invalidProperty)
                .run(context -> assertThat(context).getFailure().hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Test
    void stopsTheStartupWithoutTheLimit() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class)
                .run(context -> assertThat(context).getFailure().hasRootCauseInstanceOf(BindValidationException.class));
    }

    @EnableConfigurationProperties(SimilarProductsProperties.class)
    static class PropertiesConfig {
    }
}
