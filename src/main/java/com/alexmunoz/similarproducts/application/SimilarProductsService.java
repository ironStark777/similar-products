package com.alexmunoz.similarproducts.application;

import com.alexmunoz.similarproducts.application.DetailLookup.Found;
import com.alexmunoz.similarproducts.application.DetailLookup.NotFound;
import com.alexmunoz.similarproducts.application.DetailLookup.Reason;
import com.alexmunoz.similarproducts.application.DetailLookup.Unavailable;
import com.alexmunoz.similarproducts.domain.ProductCatalog;
import com.alexmunoz.similarproducts.domain.ProductCatalogException;
import com.alexmunoz.similarproducts.domain.ProductCatalogTimeoutException;
import com.alexmunoz.similarproducts.domain.ProductDetail;
import com.alexmunoz.similarproducts.domain.ProductNotFoundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds the list of similar products for a given one.
 *
 * <p>The whole request works within one deadline, the request budget, which covers the
 * similar ids, the waits for slots and the details. The details are looked up in parallel
 * within a {@link LookupScope}, and every call to the catalog goes through
 * {@link CatalogCallLimiter}. Each lookup ends in a {@link DetailLookup}: an expected failure
 * (the catalog fails or times out, our limit rejects the call or the deadline passes) leaves
 * that product out instead of failing the request, so it does not cancel the other lookups.
 * Anything else, such as a programming error or a cancellation, still propagates. At the
 * deadline, the request answers with the details already found.
 *
 * <p>If no similar product could be retrieved and at least one was unavailable, the request
 * fails with the most severe failure among them (see {@link Reason}). Similar products that do
 * not exist are left out.
 *
 * <p>Metrics, to tell complete answers from partial ones under load:
 * {@code similar.products.responses}, the lists answered, tagged
 * {@code result=complete|partial}, and {@code similar.products.details.unavailable}, the
 * details that could not be retrieved, tagged {@code reason=error|timeout|saturated}. A
 * similar product that does not exist does not make a list partial.
 */
@Service
public class SimilarProductsService {

    private static final Logger log = LoggerFactory.getLogger(SimilarProductsService.class);

    private final ProductCatalog productCatalog;
    private final CatalogCallLimiter catalogCalls;
    private final Duration requestBudget;
    private final NanoClock clock;
    private final Counter completeResponses;
    private final Counter partialResponses;
    private final Map<Reason, Counter> unavailableDetails = new EnumMap<>(Reason.class);

    public SimilarProductsService(ProductCatalog productCatalog,
                                  CatalogCallLimiter catalogCalls,
                                  SimilarProductsProperties properties,
                                  NanoClock clock,
                                  MeterRegistry meterRegistry) {
        this.productCatalog = productCatalog;
        this.catalogCalls = catalogCalls;
        this.requestBudget = properties.requestBudget();
        this.clock = clock;
        this.completeResponses = responsesCounter("complete", meterRegistry);
        this.partialResponses = responsesCounter("partial", meterRegistry);
        for (Reason reason : Reason.values()) {
            unavailableDetails.put(reason, Counter.builder("similar.products.details.unavailable")
                    .description("Details of similar products that could not be retrieved")
                    .tag("reason", reason.name().toLowerCase(Locale.ROOT))
                    .register(meterRegistry));
        }
    }

    /**
     * @throws ProductNotFoundException        if the given product does not exist
     * @throws ProductCatalogTimeoutException  if the similar ids, or every unavailable detail
     *                                         when none could be retrieved, timed out
     * @throws DeadlineExceededException       if the request budget ran out before the
     *                                         similar ids, or every unavailable detail when
     *                                         none could be retrieved, timed out or ran out
     *                                         of budget
     * @throws ProductCatalogException         if the catalog failed to give the similar ids,
     *                                         or any unavailable detail when none could be
     *                                         retrieved
     * @throws CatalogCallRejectedException    if the call for the similar ids, or every call
     *                                         for an unavailable detail when none could be
     *                                         retrieved, was rejected by the limit
     */
    public List<ProductDetail> getSimilarProducts(String productId) {
        Deadline deadline = Deadline.after(requestBudget, clock);
        List<String> similarIds = lookUpSimilarIds(productId, deadline);
        List<DetailLookup> lookups = lookUpDetails(productId, similarIds, deadline);

        List<ProductDetail> found = lookups.stream()
                .filter(Found.class::isInstance)
                .map(Found.class::cast)
                .map(Found::product)
                .toList();
        List<Unavailable> unavailable = lookups.stream()
                .filter(Unavailable.class::isInstance)
                .map(Unavailable.class::cast)
                .toList();
        unavailable.forEach(detail -> unavailableDetails.get(detail.reason()).increment());

        if (found.isEmpty()) {
            Optional<Unavailable> mostSevere = unavailable.stream().min(Comparator.comparing(Unavailable::reason));
            if (mostSevere.isPresent()) {
                throw mostSevere.get().failure();
            }
        }
        (unavailable.isEmpty() ? completeResponses : partialResponses).increment();
        return found;
    }

    /**
     * Looks up the similar ids within a scope too, so the request stops waiting for them at the
     * deadline; a failure is rethrown as it was thrown.
     */
    private List<String> lookUpSimilarIds(String productId, Deadline deadline) {
        try (LookupScope scope = new LookupScope(deadline)) {
            LookupScope.Subtask<List<String>> similarIds =
                    scope.fork(() -> catalogCalls.call(() -> productCatalog.findSimilarIds(productId), deadline));
            scope.join();
            return similarIds.result().orElseThrow(() -> new DeadlineExceededException(
                    "No answer within the request budget for the similar ids of product " + productId));
        }
    }

    private List<DetailLookup> lookUpDetails(String productId, List<String> similarIds, Deadline deadline) {
        record Forked(String similarId, LookupScope.Subtask<DetailLookup> lookup) {
        }
        try (LookupScope scope = new LookupScope(deadline)) {
            List<Forked> forked = similarIds.stream()
                    .distinct()
                    .map(similarId -> new Forked(similarId, scope.fork(() -> lookUpDetail(productId, similarId, deadline))))
                    .toList();
            scope.join();
            return forked.stream()
                    .map(lookup -> lookup.lookup().result().orElseGet(() -> outOfBudget(productId, lookup.similarId())))
                    .toList();
        }
    }

    private DetailLookup lookUpDetail(String productId, String similarId, Deadline deadline) {
        try {
            return catalogCalls.call(() -> productCatalog.findById(similarId), deadline)
                    .<DetailLookup>map(Found::new)
                    .orElseGet(NotFound::new);
        } catch (ProductCatalogTimeoutException | DeadlineExceededException ex) {
            return unavailable(productId, similarId, Reason.TIMEOUT, ex);
        } catch (ProductCatalogException ex) {
            return unavailable(productId, similarId, Reason.ERROR, ex);
        } catch (CatalogCallRejectedException ex) {
            return unavailable(productId, similarId, Reason.SATURATED, ex);
        }
    }

    /**
     * A lookup that had not finished at the deadline.
     */
    private static Unavailable outOfBudget(String productId, String similarId) {
        return unavailable(productId, similarId, Reason.TIMEOUT, new DeadlineExceededException(
                "No answer within the request budget for the detail of product " + similarId));
    }

    private static Counter responsesCounter(String result, MeterRegistry meterRegistry) {
        return Counter.builder("similar.products.responses")
                .description("Lists of similar products answered")
                .tag("result", result)
                .register(meterRegistry);
    }

    private static Unavailable unavailable(String productId, String similarId, Reason reason, RuntimeException failure) {
        log.debug("Similar product {} of product {} left out, {}: {}", similarId, productId, reason, failure.getMessage());
        return new Unavailable(reason, failure);
    }
}
