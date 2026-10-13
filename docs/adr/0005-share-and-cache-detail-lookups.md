# ADR-0005: Share and cache the lookups of product details

- **Status:** Accepted
- **Date:** 2026-10-13
- **Authors:** Alex Muñoz
- **Related:** ADR-0003, ADR-0004

## Context
Each k6 scenario asks for the same product again and again, and every request looked up each detail on its own, so concurrent requests made identical calls. In the last measurement before this change, `slow` and `verySlow` answered `200` to about 30 % of their requests, and 7,220 waits for a slot of the limit (ADR-0003) were rejected. The detail of a product does not need to be fresher than a few seconds for this API.

## Decision
`DetailLookupCache` puts a Caffeine `AsyncCache` in front of the lookups of details. The first request for a product starts the load on an executor owned by the cache, one virtual thread per load, and the requests that arrive while it is in progress wait for the same load. Each request waits within its own deadline with a timed `get`: running out of time or being interrupted ends only its own wait, and never completes or cancels the shared future. The load belongs to no request: it goes through the limit without a deadline, so it is bounded by the wait for a slot (100 ms) and the timeout of the HTTP client (1.5 s). A request with no time left starts no load.

A finished lookup stays cached for a time counted from the end of its load and not extended by reads: 30 s for a detail found and 5 s for a product that does not exist. An unavailable detail is cached for zero, so the next request loads it again: the future completes normally with a typed result, so Caffeine would keep it otherwise. A cached detail takes no slot. The cache holds at most 10,000 entries. The similar ids are not cached, to change one thing at a time.

Loads are shared within this instance and while the entry stays in the cache; an evicted entry, or another instance, loads the product again.

## Alternatives considered
- **Spring's `@Cacheable`:** it caches results, but does not let each request wait for a load in progress within its own deadline.
- **A map of futures written by hand:** Caffeine already gives one load per key, an expiry per entry and a bounded size.
- **Cache unavailable details for a short time:** it would cut the latency of the slow scenarios further, at the cost of not retrying a product that has recovered. The measurements decide.
- **A distributed cache:** more infrastructure than this test needs.

## Consequences
- **Positive:** concurrent requests for a product share one call, and one that joins a load in progress waits only for what is left of it; repeated requests are answered without calling the existing APIs.
- **Negative / trade-offs:** a load is no longer cancelled when the request that started it ends: it keeps its slot until it finishes or times out, for the requests that come next. A detail can be up to 30 s old, and a product created in the meantime can appear as missing for 5 s. Each instance has its own cache.
