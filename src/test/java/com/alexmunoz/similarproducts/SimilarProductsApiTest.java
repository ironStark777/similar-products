package com.alexmunoz.similarproducts;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
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
 * End-to-end tests of the agreed contract, with WireMock playing the existing APIs with
 * the data of the provided mocks (shared/simulado/mocks.json).
 */
@SpringBootTest
@AutoConfigureMockMvc
class SimilarProductsApiTest {

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
    private MeterRegistry meterRegistry;

    @BeforeEach
    void stubProvidedMocks() {
        similarIds("1", "[2,3,4]");
        similarIds("2", "[3,100,1000]");
        similarIds("3", "[100,1000,10000]");
        similarIds("4", "[1,2,5]");
        similarIds("5", "[1,2,6]");
        product("1", """
                {"id":"1","name":"Shirt","price":9.99,"availability":true}""", 0);
        product("2", """
                {"id":"2","name":"Dress","price":19.99,"availability":true}""", 0);
        product("3", """
                {"id":"3","name":"Blazer","price":29.99,"availability":false}""", 100);
        product("4", """
                {"id":"4","name":"Boots","price":39.99,"availability":true}""", 0);
        existingApis.stubFor(get("/product/5").willReturn(notFound().withBody("""
                {"message":"Product not found"}""")));
        existingApis.stubFor(get("/product/6").willReturn(serverError()));
        product("100", """
                {"id":"100","name":"Trousers","price":49.99,"availability":false}""", 1_000);
        product("1000", """
                {"id":"1000","name":"Coat","price":89.99,"availability":true}""", 5_000);
        product("10000", """
                {"id":"10000","name":"Leather jacket","price":89.99,"availability":true}""", 50_000);
    }

    @Test
    void returnsTheDetailOfTheSimilarProductsInSimilarityOrder() {
        assertThat(mvc.get().uri("/product/{productId}/similar", "1"))
                .hasStatusOk()
                .bodyJson()
                .isStrictlyEqualTo("""
                        [
                          {"id":"2","name":"Dress","price":19.99,"availability":true},
                          {"id":"3","name":"Blazer","price":29.99,"availability":false},
                          {"id":"4","name":"Boots","price":39.99,"availability":true}
                        ]""");
    }

    @Test
    void leavesOutSimilarProductsThatDoNotExist() {
        assertThat(mvc.get().uri("/product/{productId}/similar", "4"))
                .hasStatusOk()
                .bodyJson()
                .isStrictlyEqualTo("""
                        [
                          {"id":"1","name":"Shirt","price":9.99,"availability":true},
                          {"id":"2","name":"Dress","price":19.99,"availability":true}
                        ]""");
    }

    @Test
    void returnsNotFoundWhenTheProductDoesNotExist() {
        existingApis.stubFor(get("/product/7/similarids").willReturn(notFound()));

        assertThat(mvc.get().uri("/product/{productId}/similar", "7"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void recordsEachCallToTheExistingApis() {
        double similarIdsCalls = calls("/product/{productId}/similarids");
        double detailCalls = calls("/product/{productId}");

        assertThat(mvc.get().uri("/product/{productId}/similar", "1")).hasStatusOk();

        assertThat(calls("/product/{productId}/similarids") - similarIdsCalls).isEqualTo(1.0);
        assertThat(calls("/product/{productId}") - detailCalls).isEqualTo(3.0);
    }

    @Test
    void exposesHealthAndMetrics() {
        assertThat(mvc.get().uri("/product/{productId}/similar", "1")).hasStatusOk();

        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/metrics/http.client.requests")).hasStatusOk();
    }

    /**
     * Calls made to the existing APIs for a URI template, as recorded by the HTTP client
     * instrumentation.
     */
    private double calls(String uriTemplate) {
        return meterRegistry.find("http.client.requests").tag("uri", uriTemplate).timers().stream()
                .mapToDouble(Timer::count)
                .sum();
    }

    private static void similarIds(String productId, String ids) {
        existingApis.stubFor(get("/product/" + productId + "/similarids").willReturn(okJson(ids)));
    }

    private static void product(String productId, String detail, int delayMillis) {
        existingApis.stubFor(get("/product/" + productId)
                .willReturn(okJson(detail).withFixedDelay(delayMillis)));
    }
}
