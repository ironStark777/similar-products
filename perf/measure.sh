#!/usr/bin/env bash
#
# Measures the application against the five k6 scenarios of the technical test:
#   1. a full k6 run, tagged with a unique run id: requests per scenario and status, and
#      latency per scenario, read from InfluxDB for that run only,
#   2. the real calls made to the existing APIs during that run,
#   3. one request per scenario: status, time and products returned.
#
# Usage: perf/measure.sh <path to backendDevTest>
#
# Needs jq, the application on localhost:5000 and, in backendDevTest:
#   docker-compose up -d simulado influxdb grafana
# Restart the application before each measurement so that runs start from the same state.
# Results go to target/perf/<run id>/. Any failure stops the measurement, exits with an
# error and records it in run.txt, next to the diagnostics collected so far.
set -euo pipefail

harness="$(cd "${1:?Usage: perf/measure.sh <path to backendDevTest>}" && pwd)"
app="http://localhost:5000"
run_id="$(date +%Y%m%d-%H%M%S)-$$"
out="target/perf/$run_id"
similar_ids_uri="/product/%7BproductId%7D/similarids"
detail_uri="/product/%7BproductId%7D"

if command -v docker-compose > /dev/null; then
    compose="docker-compose"
else
    compose="docker compose"
fi

mkdir -p "$out"
echo "run_id: $run_id" > "$out/run.txt"

fail() {
    echo "ERROR: $1" >&2
    echo "result: FAILED - $1" >> "$out/run.txt"
    exit 1
}

# Real calls to the existing APIs for a URI template, as recorded by the application.
# A 404 means that no call has been recorded for that URI yet; any other problem fails.
calls() {
    local response status body
    response=$(curl -sS -w '\n%{http_code}' "$app/actuator/metrics/http.client.requests?tag=uri:$1") \
        || fail "could not read the metrics of $1"
    status="${response##*$'\n'}"
    body="${response%$'\n'*}"
    case "$status" in
        200) jq -er '[.measurements[] | select(.statistic == "COUNT") | .value][0] | floor' <<< "$body" \
                || fail "unexpected metrics response for $1" ;;
        404) echo 0 ;;
        *) fail "the metrics of $1 answered HTTP $status" ;;
    esac
}

influx() {
    (cd "$harness" && $compose exec -T influxdb influx -database k6 -format column -execute "$1")
}

# Status, time and products of one request.
request() {
    local product="$1" response curl_exit status_and_time body products
    set +e
    response=$(curl -sS -m 70 -w '\n%{http_code} %{time_total}' "$app/product/$product/similar" 2> /dev/null)
    curl_exit=$?
    set -e
    case "$curl_exit" in
        0) ;;
        28) echo "timeout - -"; return ;;
        *) fail "the request for product $product failed (curl exit code $curl_exit)" ;;
    esac
    status_and_time="${response##*$'\n'}"
    body="${response%$'\n'*}"
    products=$(jq -r 'if type == "array" then "[" + (map(.id) | join(",")) + "]" else "-" end' <<< "$body" \
        2> /dev/null || echo "(invalid JSON)")
    echo "${status_and_time% *} ${status_and_time#* }s $products"
}

command -v jq > /dev/null || fail "jq is required (brew install jq)"
curl -sf "$app/actuator/health" > /dev/null || fail "the application is not running on $app"
curl -sf "$app/actuator/metrics" > /dev/null || fail "the metrics endpoint is not exposed on $app"

similar_ids_before=$(calls "$similar_ids_uri")
details_before=$(calls "$detail_uri")

echo "== k6 run $run_id (about 2 minutes)"
echo "started: $(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$out/run.txt"
set +e
(cd "$harness" && $compose run --rm k6 run --tag "run_id=$run_id" scripts/test.js) > "$out/k6.txt" 2>&1
k6_exit=$?
set -e
echo "k6 exit code: $k6_exit" >> "$out/run.txt"
if [ "$k6_exit" -ne 0 ]; then
    (cd "$harness" && $compose ps) > "$out/compose-ps.txt" 2>&1 || true
    tail -n 30 "$out/k6.txt" >&2
    fail "k6 exited with code $k6_exit, see $out/k6.txt"
fi
grep -E '^ *(http_req_duration|http_reqs|iterations)\.*:' "$out/k6.txt" || echo "(no summary in the k6 output)"

echo
echo "== Calls to the existing APIs during the run"
similar_ids_after=$(calls "$similar_ids_uri")
details_after=$(calls "$detail_uri")
{
    echo "similarids: $((similar_ids_after - similar_ids_before))"
    echo "details:    $((details_after - details_before))"
} | tee "$out/calls.txt"

echo
echo "== Requests per scenario and status"
influx "SELECT count(value) AS requests FROM http_reqs WHERE run_id = '$run_id' GROUP BY scenario, status" \
    > "$out/status.txt"
[ -s "$out/status.txt" ] || fail "InfluxDB has no samples tagged run_id=$run_id"
cat "$out/status.txt"

echo
# Requests without response (status 0: cut by k6 or timed out) have no real duration, so
# they are left out of the latency and only counted in the status table above.
echo "== Latency per scenario of the answered requests (ms)"
influx "SELECT percentile(value, 50) AS p50, percentile(value, 95) AS p95, max(value) AS max FROM http_req_duration WHERE run_id = '$run_id' AND status != '0' GROUP BY scenario" \
    > "$out/latency.txt"
[ -s "$out/latency.txt" ] || fail "InfluxDB has no latency samples tagged run_id=$run_id"
cat "$out/latency.txt"

echo
echo "== One request per scenario"
{
    printf '%-9s %-8s %-8s %-11s %s\n' scenario product status time products
    for entry in normal:1 notFound:4 error:5 slow:2 verySlow:3; do
        scenario="${entry%%:*}"
        product="${entry##*:}"
        result=$(request "$product")
        read -r status time products <<< "$result"
        printf '%-9s %-8s %-8s %-11s %s\n' "$scenario" "$product" "$status" "$time" "$products"
    done
} | tee "$out/requests.txt"

echo "result: OK" >> "$out/run.txt"
echo
echo "Saved in $out"
