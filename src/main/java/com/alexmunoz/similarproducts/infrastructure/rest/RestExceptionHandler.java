package com.alexmunoz.similarproducts.infrastructure.rest;

import com.alexmunoz.similarproducts.application.CatalogCallRejectedException;
import com.alexmunoz.similarproducts.domain.ProductCatalogException;
import com.alexmunoz.similarproducts.domain.ProductCatalogTimeoutException;
import com.alexmunoz.similarproducts.domain.ProductNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Error responses. The details of the existing APIs and of our limits stay internal.
 */
@RestControllerAdvice
class RestExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    ProblemDetail handleProductNotFound(ProductNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ProductCatalogTimeoutException.class)
    ProblemDetail handleCatalogTimeout() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT,
                "The product catalog did not answer in time");
    }

    @ExceptionHandler(ProductCatalogException.class)
    ProblemDetail handleCatalogFailure() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "The product catalog did not answer properly");
    }

    /**
     * Local saturation, not a failure of the catalog: the client can try again later.
     */
    @ExceptionHandler(CatalogCallRejectedException.class)
    ProblemDetail handleCatalogCallRejected() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Too many calls to the product catalog in progress, try again later");
    }
}
