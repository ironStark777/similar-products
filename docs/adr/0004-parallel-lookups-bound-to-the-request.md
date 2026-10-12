# ADR-0004: Look up the details in parallel, bound to the request, and answer with what arrives

- **Status:** Accepted
- **Date:** 2026-10-12
- **Authors:** Alex Muñoz
- **Related:** ea124ea, ADR-0002, ADR-0003

## Context
The details of the similar products are independent of each other, but looking them up one after another adds up their latencies. The lookups belong to the request: one still running after the request has ended holds a slot (ADR-0003) whose result nobody will use. Some products fail or are very slow (up to 50 s in the mocks), and one of them should not cost the client the others.

## Decision
`SimilarProductsService` looks up the details within a `LookupScope`, one virtual thread per lookup. Each lookup ends in a typed result: found, not found, or unavailable because the existing APIs failed or timed out, or our limit rejected the call. Unavailable products are left out. Any other failure, such as a programming error, interrupts the other lookups, and a cancellation keeps its meaning. The response waits until every lookup has ended, so none outlives the request; Spring's JDK client aborts an HTTP call whose thread is interrupted.

If no product could be retrieved and one was unavailable, the request fails with the most severe reason: a failure of the existing APIs (`502`), then a timeout (`504`), then our own saturation (`503`).

Each call has a timeout of 1.5 s: the `read-timeout` of the HTTP client, which Spring's JDK client measures from sending the request until the body has been read. Once the response has arrived, it enforces the timeout by closing the body, and reading it fails with an ordinary I/O error; since the client does not say that its timer fired, the adapter takes an I/O error once the timeout has elapsed as a timeout, a heuristic that leaves parsing errors out. The tests cover the response and the body; the connection is covered because the timer starts when the request is sent. Waiting for a slot comes on top: a lookup can take about 1.6 s.

## Alternatives considered
- **A shared executor:** it can manage the tasks of each request too, provided each request cancels its own. The first version did not, and its lookups survived a failed request. A scope per request makes cancellation and the wait for cleanup part of a `try`-with-resources block.
- **`StructuredTaskScope`:** the JDK version of this idea, but a preview feature in Java 21.
- **Fail the request when any detail fails:** the previous behavior; one product cost the client all the others.
- **An empty list when nothing could be retrieved:** it would present an outage as a product without similar products.
- **The request timeout of the JDK client:** it stops at the response headers and leaves the body unbounded.

## Consequences
- **Positive:** a request takes as long as its slowest lookup, bounded by the timeout; a failing or slow product no longer costs the others.
- **Negative / trade-offs:** the client cannot tell a partial list from a complete one, since the agreed contract has no field for it. Interruption is cooperative, so the cleanup waits for a lookup that ignores it, and `join()` has no deadline. A timeout during the body is told apart by a heuristic on the elapsed time.
- **Pending:** a deadline for the whole request, covering the similar ids, the waits for slots and the details.

## Evolution
- **2026-10-12:** calls have a timeout of 1.5 s, and an expected failure of a detail leaves the product out instead of failing the request.
