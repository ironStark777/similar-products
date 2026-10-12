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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds the list of similar products for a given one.
 *
 * <p>The details of the similar products are looked up in parallel within a
 * {@link LookupScope}, and every call to the catalog goes through {@link CatalogCallLimiter}.
 * Each lookup ends in a {@link DetailLookup}: an expected failure (the catalog fails, times
 * out or our limit rejects the call) leaves that product out instead of failing the request,
 * so it does not cancel the other lookups. Anything else, such as a programming error or a
 * cancellation, still propagates.
 *
 * <p>If no similar product could be retrieved and at least one was unavailable, the request
 * fails with the most severe failure among them (see {@link Reason}). Similar products that do
 * not exist are left out.
 */
@Service
public class SimilarProductsService {

    private static final Logger log = LoggerFactory.getLogger(SimilarProductsService.class);

    private final ProductCatalog productCatalog;
    private final CatalogCallLimiter catalogCalls;

    public SimilarProductsService(ProductCatalog productCatalog, CatalogCallLimiter catalogCalls) {
        this.productCatalog = productCatalog;
        this.catalogCalls = catalogCalls;
    }

    /**
     * @throws ProductNotFoundException        if the given product does not exist
     * @throws ProductCatalogTimeoutException  if the similar ids, or every unavailable detail
     *                                         when none could be retrieved, timed out
     * @throws ProductCatalogException         if the catalog failed to give the similar ids,
     *                                         or any unavailable detail when none could be
     *                                         retrieved
     * @throws CatalogCallRejectedException    if the call for the similar ids, or every call
     *                                         for an unavailable detail when none could be
     *                                         retrieved, was rejected by the limit
     */
    public List<ProductDetail> getSimilarProducts(String productId) {
        List<String> similarIds = catalogCalls.call(() -> productCatalog.findSimilarIds(productId));
        List<DetailLookup> lookups = lookUpDetails(productId, similarIds);

        List<ProductDetail> found = lookups.stream()
                .filter(Found.class::isInstance)
                .map(Found.class::cast)
                .map(Found::product)
                .toList();
        if (found.isEmpty()) {
            Optional<Unavailable> mostSevere = lookups.stream()
                    .filter(Unavailable.class::isInstance)
                    .map(Unavailable.class::cast)
                    .min(Comparator.comparing(Unavailable::reason));
            if (mostSevere.isPresent()) {
                throw mostSevere.get().failure();
            }
        }
        return found;
    }

    private List<DetailLookup> lookUpDetails(String productId, List<String> similarIds) {
        try (LookupScope scope = new LookupScope()) {
            List<Supplier<DetailLookup>> lookups = similarIds.stream()
                    .distinct()
                    .map(similarId -> scope.fork(() -> lookUpDetail(productId, similarId)))
                    .toList();
            scope.join();
            return lookups.stream().map(Supplier::get).toList();
        }
    }

    private DetailLookup lookUpDetail(String productId, String similarId) {
        try {
            return catalogCalls.call(() -> productCatalog.findById(similarId))
                    .<DetailLookup>map(Found::new)
                    .orElseGet(NotFound::new);
        } catch (ProductCatalogTimeoutException ex) {
            return unavailable(productId, similarId, Reason.TIMEOUT, ex);
        } catch (ProductCatalogException ex) {
            return unavailable(productId, similarId, Reason.ERROR, ex);
        } catch (CatalogCallRejectedException ex) {
            return unavailable(productId, similarId, Reason.SATURATED, ex);
        }
    }

    private static Unavailable unavailable(String productId, String similarId, Reason reason, RuntimeException failure) {
        log.debug("Similar product {} of product {} left out, {}: {}", similarId, productId, reason, failure.getMessage());
        return new Unavailable(reason, failure);
    }
}
