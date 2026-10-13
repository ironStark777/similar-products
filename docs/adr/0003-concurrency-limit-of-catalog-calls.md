# ADR-0003: Limit the calls to the existing APIs in progress at the same time

- **Status:** Accepted
- **Date:** 2026-10-12
- **Authors:** Alex Muñoz
- **Related:** ea124ea, ADR-0001, ADR-0004, ADR-0005

## Context
With virtual threads (ADR-0001) and parallel lookups (ADR-0004), nothing caps how many calls go out to the existing APIs at the same time: under the k6 load, a burst of requests or a slow dependency would pull in unbounded concurrency. When our own limit is reached, the cause is local saturation, not a failure of the existing APIs, and the response should say so.

## Decision
`CatalogCallLimiter` allows at most `max-concurrent` calls in progress per instance, the similar ids and each detail alike. A call waits for a free slot at most `permit-timeout`, and never beyond the deadline of its request (ADR-0004); if none frees up, it is rejected without starting. A rejected detail leaves that product out; the request answers `503` when the similar ids are rejected, or when no product could be retrieved and every unavailable one was rejected (ADR-0004). No call holds a slot while it waits for another, so the limit cannot block itself. Both values are validated at startup and exposed with the `catalog.calls.*` metrics.

The initial values, 100 slots and 100 ms, are starting hypotheses, not derived from a known capacity of the existing APIs; a wait of 100 ms is small next to the 2 s planned for a whole request. Measurements decide whether to wait longer, fail at once or change the number of slots.

## Alternatives considered
- **Resilience4j Bulkhead:** its additional capabilities do not justify the dependency in this scope.
- **No limit:** a slow dependency would pull in unbounded concurrency.
- **Reject at once:** supported with `permit-timeout: 0`.

## Consequences
- **Positive:** the existing APIs are protected, and local saturation (`503`) is told apart from failures of the dependency.
- **Negative / trade-offs:** a slow call keeps its slot until its timeout (1.5 s, ADR-0004). Tomcat closes the connection after every `503`, so each rejection costs the client a new connection.

## Evolution
- **2026-10-12:** a rejected detail no longer fails the request: the product is left out, and `503` is kept for when nothing else can be answered.
- **2026-10-12:** the wait for a slot is bounded by the time left in the request, and a call does not start once the request has run out of budget.
- **2026-10-13:** the load of a detail is shared by the requests that wait for it (ADR-0005) and belongs to none of them, so it waits for a slot only the permit timeout. A cached detail takes no slot.
