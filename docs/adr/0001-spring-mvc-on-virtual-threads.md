# ADR-0001: Serve requests with Spring MVC on virtual threads

- **Status:** Accepted
- **Date:** 2026-10-11
- **Authors:** Alex Muñoz
- **Related:** b86eda0

## Context
Each request to `GET /product/{productId}/similar` makes several blocking HTTP calls to the existing APIs, some of them very slow (up to 50 s in the provided mocks), and the k6 test runs 200 virtual users per scenario. The work is I/O bound: threads spend most of their time waiting for responses.

## Decision
The application uses Spring MVC with imperative, blocking code, and `spring.threads.virtual.enabled=true`, so Tomcat serves each request on a virtual thread. A blocking call parks the virtual thread instead of holding a platform thread.

## Alternatives considered
- **Spring WebFlux:** this aggregator does not need its reactive capabilities, and we prefer the imperative model, simpler to write, test and debug.
- **Spring MVC on a platform thread pool:** slow calls would tie up the pool (200 threads by default in Tomcat) and requests would queue behind them.

## Consequences
- **Positive:** plain imperative code and ordinary tests; a slow dependency does not exhaust server threads.
- **Negative / trade-offs:** requires Java 21. Nothing limits how many calls go out at the same time any more, so the limit has to be explicit (ADR-0003). On Java 21, blocking inside a `synchronized` block pins the carrier thread; Java 24 removes that restriction.
