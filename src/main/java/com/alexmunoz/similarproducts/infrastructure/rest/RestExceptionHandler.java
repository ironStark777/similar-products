package com.alexmunoz.similarproducts.infrastructure.rest;

import com.alexmunoz.similarproducts.application.CatalogCallRejectedException;
import com.alexmunoz.similarproducts.domain.ProductNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class RestExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    ProblemDetail handleProductNotFound(ProductNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * Local saturation, not a failure of the catalog: the client can try again later. The
     * details of the limit stay internal.
     */
    @ExceptionHandler(CatalogCallRejectedException.class)
    ProblemDetail handleCatalogCallRejected() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Too many calls to the product catalog in progress, try again later");
    }
}
