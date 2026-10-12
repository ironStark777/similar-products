package com.alexmunoz.similarproducts;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.alexmunoz.similarproducts.application.CatalogCallLimiter;
import com.alexmunoz.similarproducts.application.Deadline;
import com.alexmunoz.similarproducts.application.NanoClock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The limit of calls to the existing APIs, end to end, with a single slot and no waiting.
 */
@SpringBootTest(properties = {
        "similar-products.catalog-calls.max-concurrent=1",
        "similar-products.catalog-calls.permit-timeout=0ms"})
@AutoConfigureMockMvc
class CatalogCallLimitApiTest {

    @RegisterExtension
    static final WireMockExtension existingApis = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void existingApisBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.http.serviceclient.products.base-url", existingApis::baseUrl);
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private CatalogCallLimiter catalogCalls;

    @Test
    void answersServiceUnavailableWithoutCallingTheExistingApisWhenNoSlotIsFree() throws InterruptedException {
        CountDownLatch slotTaken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Deadline distantDeadline = Deadline.after(Duration.ofMinutes(1), NanoClock.system());
        Thread slotHolder = Thread.ofPlatform().daemon().start(() -> catalogCalls.call(() -> {
            slotTaken.countDown();
            awaitQuietly(release);
            return null;
        }, distantDeadline));
        assertThat(slotTaken.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            assertThat(mvc.get().uri("/product/{productId}/similar", "1"))
                    .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .hasContentTypeCompatibleWith("application/problem+json");
        } finally {
            release.countDown();
            slotHolder.join(5_000);
        }
        existingApis.verify(0, anyRequestedFor(anyUrl()));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
