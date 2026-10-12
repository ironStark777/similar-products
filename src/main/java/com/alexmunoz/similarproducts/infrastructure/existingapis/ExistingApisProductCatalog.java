package com.alexmunoz.similarproducts.infrastructure.existingapis;

import com.alexmunoz.similarproducts.domain.ProductCatalog;
import com.alexmunoz.similarproducts.domain.ProductCatalogException;
import com.alexmunoz.similarproducts.domain.ProductCatalogTimeoutException;
import com.alexmunoz.similarproducts.domain.ProductDetail;
import com.alexmunoz.similarproducts.domain.ProductNotFoundException;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link ProductCatalog} backed by the existing APIs. Only an explicit {@code 404} means that a
 * product does not exist; any other failure is reported as a catalog failure, telling a
 * timeout apart.
 */
@Component
class ExistingApisProductCatalog implements ProductCatalog {

    private final ExistingProductsApi existingProductsApi;
    private final Duration timeout;

    /**
     * @param timeout the timeout of each call, configured on the HTTP client
     */
    ExistingApisProductCatalog(ExistingProductsApi existingProductsApi,
                               @Value("${spring.http.serviceclient.products.read-timeout}") Duration timeout) {
        this.existingProductsApi = existingProductsApi;
        this.timeout = timeout;
    }

    @Override
    public List<String> findSimilarIds(String productId) {
        String what = "similar ids of product " + productId;
        return call(what, () -> {
            try {
                return requireBody(existingProductsApi.getSimilarProductIds(productId), what);
            } catch (HttpClientErrorException.NotFound ex) {
                throwIfInterrupted(what, ex);
                throw new ProductNotFoundException(productId);
            }
        });
    }

    @Override
    public Optional<ProductDetail> findById(String productId) {
        String what = "detail of product " + productId;
        return call(what, () -> {
            try {
                return Optional.of(requireBody(existingProductsApi.getProductDetail(productId), what));
            } catch (HttpClientErrorException.NotFound ex) {
                throwIfInterrupted(what, ex);
                return Optional.empty();
            }
        });
    }

    /**
     * Makes a call and translates the failures of the HTTP client into the domain.
     */
    private <T> T call(String what, Supplier<T> call) {
        long start = System.nanoTime();
        try {
            return call.get();
        } catch (RestClientException ex) {
            throwIfInterrupted(what, ex);
            if (ex instanceof RestClientResponseException response) {
                throw new ProductCatalogException(
                        "The existing APIs answered " + response.getStatusCode().value() + " for the " + what, ex);
            }
            if (isTimeout(ex, start)) {
                throw new ProductCatalogTimeoutException("No complete answer within " + timeout + " for the " + what, ex);
            }
            throw new ProductCatalogException("The call for the " + what + " failed", ex);
        }
    }

    /**
     * The HTTP client measures the timeout from sending the request until the body has been
     * read. If it expires before the response arrives, the failure is an
     * {@link HttpTimeoutException}. Once the response has arrived, the client closes the body
     * instead, and reading it fails with an ordinary I/O error.
     *
     * <p>The client does not say that its timer fired, so an I/O error once the timeout has
     * elapsed is taken as that timeout: a heuristic. A response that cannot be parsed is not
     * an I/O error, so it stays a failure however late it is found.
     */
    private boolean isTimeout(RestClientException ex, long start) {
        boolean ioError = false;
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException) {
                return true;
            }
            ioError |= cause instanceof IOException;
        }
        return ioError && System.nanoTime() - start >= timeout.toNanos();
    }

    /**
     * A call interrupted by its caller is cancelled, whatever the status it got, a {@code 404}
     * included: the interrupt may have cut the reading of the response.
     */
    private static void throwIfInterrupted(String what, RestClientException ex) {
        if (Thread.currentThread().isInterrupted()) {
            CancellationException cancelled = new CancellationException("Interrupted while waiting for the " + what);
            cancelled.initCause(ex);
            throw cancelled;
        }
    }

    private static <T> T requireBody(T body, String what) {
        if (body == null) {
            throw new ProductCatalogException("Empty response for the " + what);
        }
        return body;
    }
}
