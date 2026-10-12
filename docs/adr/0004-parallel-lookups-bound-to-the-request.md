# ADR-0004: Look up the details in parallel, within a request budget, and answer with what arrives

- **Status:** Accepted
- **Date:** 2026-10-12
- **Authors:** Alex Muñoz
- **Related:** ea124ea, ADR-0002, ADR-0003

## Context
The details of the similar products are independent of each other, but looking them up one after another adds up their latencies. The lookups belong to the request: one still running after the request has ended holds a slot (ADR-0003) whose result nobody will use. Some products fail or are very slow (up to 50 s in the mocks), and one of them should not cost the client the others.

## Decision
`SimilarProductsService` looks up the details within a `LookupScope`, one virtual thread per lookup. Each lookup ends in a typed result: found, not found, or unavailable because the existing APIs failed or timed out, our limit rejected the call or the request ran out of budget. Unavailable products are left out. Any other failure, such as a programming error, interrupts the other lookups, and a cancellation keeps its meaning.

Each request has a budget of 2 s (`similar-products.request-budget`), an absolute deadline set when it starts that covers the similar ids, the waits for slots and the details. The similar ids are awaited in a scope as well, so the request stops waiting for them at the deadline. A call waits for a slot at most until the deadline; if the deadline ends that wait, it counts as the end of the budget, not as saturation, and a call that gets its slot after the deadline frees it without starting. At the deadline, the scope takes the results finished by then and requests the cancellation of the rest by interrupting them: a lookup waiting for a slot leaves without starting its HTTP call, and Spring's JDK client aborts one in progress. The response does not wait for that cleanup beyond the deadline. A lookup that does not stop keeps its slot and counts as pending until it really ends. The budget bounds the processing, not the moment the client receives the response: scheduling, serialization and transport come on top.

If no product could be retrieved and one was unavailable, the request fails with the most severe reason: a failure of the existing APIs (`502`), then a timeout or the end of the budget (`504`), then our own saturation (`503`).

Each call has a timeout of 1.5 s: the `read-timeout` of the HTTP client, which Spring's JDK client measures from sending the request until the body has been read. Once the response has arrived, it enforces the timeout by closing the body, and reading it fails with an ordinary I/O error; since the client does not say that its timer fired, the adapter takes an I/O error once the timeout has elapsed as a timeout, a heuristic that leaves parsing errors out. The tests cover the response and the body; the connection is covered because the timer starts when the request is sent. Waiting for a slot comes on top of it, within the request budget.

## Alternatives considered
- **A shared executor:** it can manage the tasks of each request too, provided each request cancels its own. The first version did not, and its lookups survived a failed request. A scope per request makes cancellation and the wait for cleanup part of a `try`-with-resources block.
- **`StructuredTaskScope`:** the JDK version of this idea, but a preview feature in Java 21.
- **Fail the request when any detail fails:** the previous behavior; one product cost the client all the others.
- **An empty list when nothing could be retrieved:** it would present an outage as a product without similar products.
- **The request timeout of the JDK client:** it stops at the response headers and leaves the body unbounded.
- **Wait for every lookup to end before answering:** no lookup would outlive the request, but a lookup that does not stop would hold the response past the budget.

## Consequences
- **Positive:** a request takes as long as its slowest lookup, and its processing stays within the budget; a failing or slow product no longer costs the others.
- **Negative / trade-offs:** the client cannot tell a partial list from a complete one, since the agreed contract has no field for it; the `similar.products.responses` metric counts both, and `similar.products.details.unavailable` why details were missing. Interruption only requests the cancellation: a lookup that does not respond to it keeps running after the response, and its HTTP timeout is a second protection, not a guarantee that it ends. A timeout during the body is told apart by a heuristic on the elapsed time.

## Evolution
- **2026-10-12:** calls have a timeout of 1.5 s, and an expected failure of a detail leaves the product out instead of failing the request.
- **2026-10-12:** each request has a budget of 2 s; at the deadline it answers with what has arrived, without waiting for the cleanup.
