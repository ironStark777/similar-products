package com.alexmunoz.similarproducts.infrastructure.existingapis;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.badRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.alexmunoz.similarproducts.domain.ProductCatalogException;
import com.alexmunoz.similarproducts.domain.ProductCatalogTimeoutException;
import com.alexmunoz.similarproducts.domain.ProductDetail;
import com.alexmunoz.similarproducts.domain.ProductNotFoundException;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * The adapter against WireMock playing the existing APIs, without a Spring context, with the
 * same HTTP client as the application and a short timeout.
 */
class ExistingApisProductCatalogTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);
    /** Margin over the timeout for scheduling and clean-up: far below the delays used. */
    private static final Duration TIMEOUT_WITH_MARGIN = Duration.ofSeconds(2);
    private static final String DETAIL = """
            {"id":"1","name":"Shirt","price":9.99,"availability":true}""";

    @RegisterExtension
    static final WireMockExtension existingApis = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final ExistingApisProductCatalog catalog = new ExistingApisProductCatalog(client(), TIMEOUT);

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
                .isExactlyInstanceOf(ProductCatalogException.class);
    }

    @Test
    void reportsSimilarIdsThatDoNotArriveInTime() {
        existingApis.stubFor(get("/product/1/similarids").willReturn(okJson("[2,3,4]").withFixedDelay(5_000)));

        assertTimesOut(() -> catalog.findSimilarIds("1"));
    }

    @Test
    void returnsTheDetailOfAProduct() {
        existingApis.stubFor(get("/product/1").willReturn(okJson(DETAIL)));

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
                .isExactlyInstanceOf(ProductCatalogException.class);
    }

    @Test
    void reportsAResponseThatDoesNotArriveInTime() {
        existingApis.stubFor(get("/product/1").willReturn(okJson(DETAIL).withFixedDelay(5_000)));

        assertTimesOut(() -> catalog.findById("1"));
    }

    @Test
    void reportsABodyThatDoesNotArriveInTime() {
        // The status and headers arrive at once; the body takes 5 s
        existingApis.stubFor(get("/product/1").willReturn(okJson(DETAIL).withChunkedDribbleDelay(10, 5_000)));

        assertTimesOut(() -> catalog.findById("1"));
    }

    @Test
    void reportsAnErrorStatusAsAFailure() {
        existingApis.stubFor(get("/product/6").willReturn(serverError()));

        assertThatThrownBy(() -> catalog.findById("6"))
                .isExactlyInstanceOf(ProductCatalogException.class);
    }

    @Test
    void reportsAnUnexpectedClientErrorAsAFailure() {
        existingApis.stubFor(get("/product/1").willReturn(badRequest()));

        assertThatThrownBy(() -> catalog.findById("1"))
                .isExactlyInstanceOf(ProductCatalogException.class);
    }

    @Test
    void reportsABrokenConnectionAsAFailure() {
        existingApis.stubFor(get("/product/1").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> catalog.findById("1"))
                .isExactlyInstanceOf(ProductCatalogException.class);
    }

    @Test
    void reportsAnUnreadableResponseAsAFailure() {
        existingApis.stubFor(get("/product/1").willReturn(okJson("{\"id\":")));

        assertThatThrownBy(() -> catalog.findById("1"))
                .isExactlyInstanceOf(ProductCatalogException.class);
    }

    @Test
    void cancelsACallInterruptedWhileWaitingForTheResponse() throws InterruptedException {
        existingApis.stubFor(get("/product/1").willReturn(okJson(DETAIL).withFixedDelay(5_000)));

        assertThat(callInterruptedAfter(Duration.ZERO, catalog -> catalog.findById("1")))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void cancelsACallInterruptedWhileReadingTheBodyOfAnError() throws InterruptedException {
        // The 500 status arrives at once; its body takes 5 s, and is read to build the error
        existingApis.stubFor(get("/product/1").willReturn(serverError()
                .withBody("{\"message\":\"The detail of product 1 failed\"}")
                .withChunkedDribbleDelay(10, 5_000)));

        assertThat(callInterruptedAfter(Duration.ofMillis(300), catalog -> catalog.findById("1")))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void cancelsADetailCallInterruptedWhileReadingTheBodyOfANotFound() throws InterruptedException {
        existingApis.stubFor(get("/product/1").willReturn(slowNotFoundBody()));

        assertThat(callInterruptedAfter(Duration.ofMillis(300), catalog -> catalog.findById("1")))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void cancelsASimilarIdsCallInterruptedWhileReadingTheBodyOfANotFound() throws InterruptedException {
        existingApis.stubFor(get("/product/1/similarids").willReturn(slowNotFoundBody()));

        assertThat(callInterruptedAfter(Duration.ofMillis(300), catalog -> catalog.findSimilarIds("1")))
                .isInstanceOf(CancellationException.class);
    }

    /**
     * A 404 that arrives at once, with a body that takes 5 s.
     */
    private static ResponseDefinitionBuilder slowNotFoundBody() {
        return notFound()
                .withBody("{\"message\":\"Product not found\"}")
                .withChunkedDribbleDelay(10, 5_000);
    }

    /**
     * Makes the call with a long timeout and interrupts it once it has been waiting for the
     * given time.
     *
     * @return what the call threw
     */
    private static Throwable callInterruptedAfter(Duration wait, Consumer<ExistingApisProductCatalog> call)
            throws InterruptedException {
        ExistingApisProductCatalog patientCatalog = new ExistingApisProductCatalog(client(Duration.ofSeconds(10)),
                Duration.ofSeconds(10));
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread caller = Thread.ofPlatform().daemon().start(() -> {
            try {
                call.accept(patientCatalog);
            } catch (RuntimeException ex) {
                failure.set(ex);
            }
        });
        awaitWaiting(caller);
        Thread.sleep(wait.toMillis());
        caller.interrupt();
        caller.join(TIMEOUT_WITH_MARGIN.toMillis());
        return failure.get();
    }

    private static void assertTimesOut(Runnable call) {
        long start = System.nanoTime();

        assertThatThrownBy(call::run).isInstanceOf(ProductCatalogTimeoutException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(TIMEOUT_WITH_MARGIN);
    }

    /**
     * Waits until the thread is blocked waiting for the response.
     */
    private static void awaitWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT_WITH_MARGIN.toNanos();
        while (thread.getState() != Thread.State.WAITING) {
            if (System.nanoTime() > deadline) {
                fail("The call did not start waiting for the response");
            }
            Thread.sleep(1);
        }
    }

    private static ExistingProductsApi client() {
        return client(TIMEOUT);
    }

    /**
     * The HTTP client of the application: the JDK client on HTTP/1.1, with a read timeout.
     */
    private static ExistingProductsApi client(Duration timeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build());
        requestFactory.setReadTimeout(timeout);
        RestClient restClient = RestClient.builder()
                .baseUrl(existingApis.baseUrl())
                .requestFactory(requestFactory)
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(ExistingProductsApi.class);
    }
}
