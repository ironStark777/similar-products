# ADR-0002: Reach the existing APIs through a port and a declarative HTTP client

- **Status:** Accepted
- **Date:** 2026-10-11
- **Authors:** Alex Muñoz
- **Related:** 87ca908, 8629562

## Context
The service builds its answer from two existing endpoints (`existingApis.yaml`): the similar ids of a product and the detail of a product. The application logic should not depend on HTTP, so it can be tested without a server, and the HTTP side should be tested against real responses.

## Decision
The domain defines the `ProductCatalog` port. `ExistingApisProductCatalog` implements it with `ExistingProductsApi`, an HTTP service interface (`@HttpExchange`) configured through `spring.http.serviceclient.products.*`. Only an explicit `404` means that a product does not exist; any other failure, including a `200` without body, becomes a `ProductCatalogException`, or a `ProductCatalogTimeoutException` when the call timed out (ADR-0004). The JDK `HttpClient` is forced to HTTP/1.1: by default it attempts an HTTP/2 (h2c) upgrade on plain http connections, which the existing APIs do not need.

## Alternatives considered
- **`RestClient` used directly in the adapter:** keeps the port as well, with finer control of each call, but every endpoint needs its own request and response handling. The declarative interface states both endpoints in one place, and Spring Boot configures it per group.
- **Client generated from `existingApis.yaml` with OpenAPI Generator:** generated code and a build plugin for two endpoints.
- **`WebClient`:** reactive, not needed with virtual threads (ADR-0001).

## Consequences
- **Positive:** service tests use `FakeProductCatalog`; adapter tests run against WireMock without Spring; the HTTP client records every call in `http.client.requests`, tagged with its URI template.
- **Negative / trade-offs:** with HTTP/1.1 a connection carries one request at a time, so the connection pool grows with the concurrent calls.
