package com.alexmunoz.similarproducts.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the similar products feature. They are validated at startup, so a wrong value
 * stops the application instead of changing its behavior.
 *
 * @param catalogCalls limit of the calls to the product catalog
 */
@Validated
@ConfigurationProperties("similar-products")
public record SimilarProductsProperties(@NotNull @Valid CatalogCalls catalogCalls) {

    /**
     * @param maxConcurrent calls to the catalog allowed in progress at the same time
     * @param permitTimeout how long a call waits for a free slot before it is rejected; zero
     *                      rejects it at once
     */
    public record CatalogCalls(
            @Positive int maxConcurrent,
            @NotNull @DurationMin(millis = 0) Duration permitTimeout) {
    }
}
