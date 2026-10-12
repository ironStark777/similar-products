# ADR-0004: Look up the details in parallel, bound to the request

- **Status:** Accepted
- **Date:** 2026-10-12
- **Authors:** Alex Muñoz
- **Related:** ea124ea, ADR-0003

## Context
The details of the similar products are independent of each other, but looking them up one after another adds up their latencies. The lookups belong to the request: one still running after the request has failed holds a slot (ADR-0003) whose result nobody will use.

## Decision
`SimilarProductsService` looks up the details within a `LookupScope`, one virtual thread per lookup. When a lookup fails, the scope detects it and interrupts the others; the response waits until all of them have ended, so none outlives the request. An interrupted lookup still waiting for a slot never starts, and one in progress is aborted: Spring's JDK client cancels the HTTP exchange when its thread is interrupted. The scope follows the fork, join and close shape of the JDK structured concurrency, still a preview feature in Java 21.

## Alternatives considered
- **A shared executor:** it can manage the tasks of each request too, provided each request cancels its own tasks. The first version ran them on `CompletableFuture`s joined in order without that cancellation: the remaining lookups survived a failed request, and a failure was only seen after the lookups before it had finished. A scope per request makes cancellation and the wait for cleanup part of a `try`-with-resources block.
- **`StructuredTaskScope`:** the JDK version of this idea, but a preview feature in Java 21 (`--enable-preview`).

## Consequences
- **Positive:** a request takes as long as its slowest lookup, not the sum; when it fails, no lookup keeps a slot.
- **Negative / trade-offs:** interruption is cooperative, so the cleanup waits for a lookup that ignores it; `join()` neither has a deadline nor responds to interrupts.
- **Pending:** expected failures of a detail should not cancel the others; per-call timeouts and a deadline for the whole request.
