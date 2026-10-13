package com.alexmunoz.similarproducts;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
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
        // Products that only this test asks for, so no other test has cached them
        similarIds("11", "[12,13]");
        product("12", """
                {"id":"12","name":"Hat","price":14.99,"availability":true}""", 0);
        product("13", """
                {"id":"13","name":"Scarf","price":24.99,"availability":true}""", 0);
        double similarIdsCalls = calls("/product/{productId}/similarids");
        double detailCalls = calls("/product/{productId}");

        assertThat(mvc.get().uri("/product/{productId}/similar", "11")).hasStatusOk();

        assertThat(calls("/product/{productId}/similarids") - similarIdsCalls).isEqualTo(1.0);
        assertThat(calls("/product/{productId}") - detailCalls).isEqualTo(2.0);
    }

    @Test
    void answersCachedDetailsWithoutCallingTheExistingApisAgain() {
        // Products that only this test asks for, so no other test has cached them
        similarIds("14", "[15,16]");
        product("15", """
                {"id":"15","name":"Belt","price":19.99,"availability":true}""", 0);
        product("16", """
                {"id":"16","name":"Gloves","price":29.99,"availability":false}""", 0);
        assertThat(mvc.get().uri("/product/{productId}/similar", "14")).hasStatusOk();
        double similarIdsCalls = calls("/product/{productId}/similarids");
        double detailCalls = calls("/product/{productId}");

        assertThat(mvc.get().uri("/product/{productId}/similar", "14"))
                .hasStatusOk()
                .bodyJson()
                .isStrictlyEqualTo("""
                        [
                          {"id":"15","name":"Belt","price":19.99,"availability":true},
                          {"id":"16","name":"Gloves","price":29.99,"availability":false}
                        ]""");

        // The similar ids are not cached
        assertThat(calls("/product/{productId}/similarids") - similarIdsCalls).isEqualTo(1.0);
        assertThat(calls("/product/{productId}") - detailCalls).isZero();
    }

    @Test
    void leavesOutSimilarProductsThatFail() {
        // Product 6 answers 500
        assertThat(mvc.get().uri("/product/{productId}/similar", "5"))
                .hasStatusOk()
                .bodyJson()
                .isStrictlyEqualTo("""
                        [
                          {"id":"1","name":"Shirt","price":9.99,"availability":true},
                          {"id":"2","name":"Dress","price":19.99,"availability":true}
                        ]""");
    }

    @Test
    void leavesOutSimilarProductsThatTimeOutAndFreesTheirSlots() {
        // Product 1000 answers after 5 s, beyond the timeout of 1.5 s; product 100, after 1 s
        assertTimeout(Duration.ofSeconds(4), () -> assertThat(mvc.get().uri("/product/{productId}/similar", "2"))
                .hasStatusOk()
                .bodyJson()
                .isStrictlyEqualTo("""
                        [
                          {"id":"3","name":"Blazer","price":29.99,"availability":false},
                          {"id":"100","name":"Trousers","price":49.99,"availability":false}
                        ]"""));
        assertThat(meterRegistry.get("catalog.calls.active").gauge().value()).isZero();
    }

    @Test
    void answersWithinTheRequestBudget() {
        // The similar ids take 1.4 s and product 1000 only times out 1.5 s later, but the
        // request budget is 2 s: without it, the request would take 2.9 s
        existingApis.stubFor(get("/product/8/similarids").willReturn(okJson("[1000]").withFixedDelay(1_400)));

        assertTimeout(Duration.ofMillis(2_600), () -> assertThat(mvc.get().uri("/product/{productId}/similar", "8"))
                .hasStatus(HttpStatus.GATEWAY_TIMEOUT));
    }

    @Test
    void answersGatewayTimeoutWhenTheSimilarIdsDoNotArriveInTime() {
        existingApis.stubFor(get("/product/8/similarids").willReturn(okJson("[1]").withFixedDelay(5_000)));

        assertThat(mvc.get().uri("/product/{productId}/similar", "8"))
                .hasStatus(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void answersBadGatewayWhenTheSimilarIdsFail() {
        existingApis.stubFor(get("/product/8/similarids").willReturn(serverError()));

        assertThat(mvc.get().uri("/product/{productId}/similar", "8"))
                .hasStatus(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void answersBadGatewayWhenNoSimilarProductCouldBeRetrieved() {
        // Product 6 answers 500
        similarIds("8", "[6]");

        assertThat(mvc.get().uri("/product/{productId}/similar", "8"))
                .hasStatus(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void answersGatewayTimeoutWhenEverySimilarProductTimedOut() {
        // Product 1000 answers after 5 s, beyond the timeout of 1.5 s
        similarIds("8", "[1000]");

        assertThat(mvc.get().uri("/product/{productId}/similar", "8"))
                .hasStatus(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void exposesHealthAndMetrics() {
        assertThat(mvc.get().uri("/product/{productId}/similar", "1")).hasStatusOk();

        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/metrics/http.client.requests")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/metrics/catalog.calls.pending")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/metrics/catalog.calls.active")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/metrics/catalog.calls.active.peak")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/metrics/catalog.calls.permit.wait")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/metrics/similar.products.responses")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/metrics/similar.products.details.unavailable")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/metrics/detail.cache.lookups")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/metrics/detail.cache.loads")).hasStatusOk();
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
