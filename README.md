# Similar products

Spring Boot application for the similar products API agreed in the backend technical test, served on port 5000:

```
GET /product/{productId}/similar
```

It answers the detail of each product similar to the given one, in similarity order, built from the existing APIs on port 3001 (`/product/{productId}/similarids` and `/product/{productId}`).

## Running it

Requirements: Java 21 or later. Maven comes with the Maven Wrapper (`./mvnw`).

```bash
./mvnw spring-boot:run
```

To run the tests:

```bash
./mvnw verify
```

## Measuring it

`perf/measure.sh` runs the k6 scenarios of the test and reports, for that run only, the status and latency per scenario, the calls made to the existing APIs, the concurrency limit, the content of the lists and the detail cache. It needs `jq`, the application on port 5000 and, in the `backendDevTest` repository, `docker-compose up -d simulado influxdb grafana`.

```bash
docker-compose -f ../backendDevTest/docker-compose.yaml restart simulado
./mvnw spring-boot:run                      # restarted before each measurement
perf/measure.sh ../backendDevTest           # in another terminal
```

Results are saved in `target/perf/<run id>/`, which `./mvnw clean` deletes.

## Behavior

| Situation | Response |
|---|---|
| Similar products found | `200` with their details, in similarity order |
| Some similar products do not exist, fail or do not answer in time | `200` without them |
| The product does not exist | `404` |
| No detail could be retrieved, or the similar ids failed | `502` if the existing APIs failed, `504` if they did not answer in time or the request ran out of budget, `503` if our concurrency limit rejected the calls |

Errors are answered as `application/problem+json`.

## Design

- **Spring MVC on virtual threads:** blocking, imperative code without tying up platform threads ([ADR-0001](docs/adr/0001-spring-mvc-on-virtual-threads.md)).
- **The existing APIs behind a port:** a declarative HTTP client with a timeout of 1.5 s per call, covering the connection, the response and the body ([ADR-0002](docs/adr/0002-existing-apis-behind-a-port.md)).
- **A concurrency limit:** at most 100 calls to the existing APIs in progress, each waiting at most 100 ms for a slot ([ADR-0003](docs/adr/0003-concurrency-limit-of-catalog-calls.md)).
- **Parallel lookups within a request budget of 2 s:** the request answers with the details that have arrived, leaving out the products that fail or are too slow ([ADR-0004](docs/adr/0004-parallel-lookups-bound-to-the-request.md)).
- **Shared and cached details:** concurrent requests for a product share one call, and its result is cached for 30 s, or 5 s if it does not exist; unavailable details are not cached ([ADR-0005](docs/adr/0005-share-and-cache-detail-lookups.md)).

The settings are under `similar-products` in `application.yml` and are validated at startup.

## Results

Measured on 2026-10-13 on a laptop with Docker, with the k6 scenarios of the test (200 virtual users for 10 s each). The application and the mocks were restarted before each run, and each version was run twice: the tables show both values, or their range. One run of the version before the cache was repeated because its restart could not be confirmed. Latencies are the median of the answered requests (`200`); a `503` answered at once does not count in them. The output of each run is in [docs/perf/2026-10-13](docs/perf/2026-10-13).

| Scenario | Before the cache (`1ecde4a`) | With the cache (`b54538f`) |
|---|---|---|
| normal | 99.8–99.9 % `200`, 111–112 ms | 100 % `200`, 15–23 ms |
| notFound | 100 % `200`, 6–8 ms | 100 % `200`, 4–8 ms |
| error | 99.8–100 % `200`, 9 ms | 100 % `200`, 4–15 ms |
| slow | 25–27 % `200`, 1.58–1.60 s | 100 % `200`, 1.48 s |
| verySlow | 19–28 % `200`, 1.52–1.54 s | 100 % `200`, 1.48 s |

| Whole run | Before the cache | With the cache |
|---|---|---|
| Calls for details | 35,187 / 35,132 | 1,599 / 1,207 |
| Waits for a slot rejected | 4,669 / 5,636 | 0 / 0 |
| Requests without response | 182 / 79 | 0 / 0 |
| Detail lookups answered without a load of their own | — | 96.1 % / 97.0 % |

Before the cache, most requests of `slow` and `verySlow` were rejected by the concurrency limit with `503`, and some got no response at all (`dial tcp: i/o timeout` in k6). With the cache, concurrent requests share the load of each product and none is rejected. The lists of `slow` and `verySlow` still take about 1.5 s and stay partial: they wait for products 1000 and 10000 until the timeout. The 200 virtual users of each scenario start at the same time, join the same load and wait for all of it, so joining a load in progress shortens little here. The cache does not change the latency of `notFound` and `error` beyond the variation between runs. A second run without restarting the application, with the cache already warm, was not measured.

About 98 % of the remaining loads are product 6, which answers `500`: unavailable details are not cached, and that error comes so fast that few requests share it. Caching them for a few seconds would remove most of those calls, at the cost of not retrying a product that may have recovered (ADR-0005).

The first version, sequential and without timeouts (`e67ed45`), was measured once on 2026-10-12, in an environment that turned out to vary between sessions, so it is kept only as history: `error` answered `500` to all 3,469 requests it answered, `slow` answered 312 requests with a median of 6.15 s, and `verySlow` answered none within the run.

## Known limitations

- The agreed contract has no field to tell a partial list from a complete one; the metric `similar.products.responses` counts both.
- In the mocks, products 1000 and 10000 take 5 s and 50 s, beyond the timeout of 1.5 s, so the lists of products 2 and 3 are always partial.
- A detail can be up to 30 s old, and each instance has its own cache.
- A timeout while reading the body is told apart from other I/O errors by the time elapsed ([ADR-0004](docs/adr/0004-parallel-lookups-bound-to-the-request.md)).

## Metrics

Exposed on `/actuator/metrics`:

- `http.client.requests`: calls to the existing APIs, by URI template.
- `catalog.calls.pending`, `catalog.calls.active`, `catalog.calls.active.peak` and `catalog.calls.permit.wait`: the concurrency limit.
- `similar.products.responses` and `similar.products.details.unavailable`: complete and partial lists, and why details were missing.
- `detail.cache.lookups` and `detail.cache.loads`: how the cache answered, and the result of each load.
