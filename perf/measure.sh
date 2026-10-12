#!/usr/bin/env bash
#
# Measures the application against the five k6 scenarios of the technical test:
#   1. a full k6 run, tagged with a unique run id: requests and latency per scenario and
#      status, read from InfluxDB for that run only, and why the requests without response
#      failed, from the k6 warnings,
#   2. the real calls made to the existing APIs during that run, with their mean duration,
#      and how their concurrency limit behaved: most calls in progress at once, slots
#      granted and rejected. Calls still pending when k6 ends are waited for first, so that
#      they count and do not overlap with the next step: until no call is pending and no slot
#      is granted or rejected in several samples in a row, within a deadline,
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
# Longest wait for the calls still in progress when k6 ends; the slowest mock answers after 50 s
max_pending_wait=90
# Samples in a row, one second apart, with no call pending and no slot granted or rejected for
# the application to count as idle. catalog.calls.pending counts the calls waiting for a slot
# or in progress, but not a task about to enter the limiter, nor the moment between getting
# the similar ids and starting the lookups of their details: the samples cover those gaps in
# practice, though they are not a guarantee
settled_samples=3
# Longest time for each read of the application metrics, in seconds
metrics_max_time=5

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

# A statistic (COUNT, or TOTAL_TIME in seconds) of the real calls to the existing APIs for a
# URI template, as recorded by the application. A 404 means that no call has been recorded
# for that URI yet; any other problem fails.
calls() {
    local uri="$1" statistic="$2" response status body
    response=$(curl -sS --connect-timeout 2 --max-time "$metrics_max_time" -w '\n%{http_code}' \
        "$app/actuator/metrics/http.client.requests?tag=uri:$uri") \
        || fail "could not read the metrics of $uri"
    status="${response##*$'\n'}"
    body="${response%$'\n'*}"
    case "$status" in
        200) jq -er --arg statistic "$statistic" \
                '[.measurements[] | select(.statistic == $statistic) | .value][0] | if . == floor then floor else . end' \
                <<< "$body" \
                || fail "unexpected metrics response for $uri" ;;
        404) echo 0 ;;
        *) fail "the metrics of $uri answered HTTP $status" ;;
    esac
}

# A statistic of an application metric, optionally filtered by a tag (name:value), or "-"
# if the metric does not exist (a version of the application without it).
metric() {
    local name="$1" statistic="$2" tag="${3:-}" response status body
    response=$(curl -sS --connect-timeout 2 --max-time "$metrics_max_time" -w '\n%{http_code}' \
        "$app/actuator/metrics/$name${tag:+?tag=$tag}") \
        || fail "could not read the metric $name"
    status="${response##*$'\n'}"
    body="${response%$'\n'*}"
    case "$status" in
        200) jq -er --arg statistic "$statistic" \
                '[.measurements[] | select(.statistic == $statistic) | .value][0] | if . == floor then floor else . end' \
                <<< "$body" \
                || fail "unexpected response for the metric $name" ;;
        404) echo "-" ;;
        *) fail "the metric $name answered HTTP $status" ;;
    esac
}

# A metric read while waiting for the pending calls: within the deadline of that wait, which
# fails once it has passed.
pending_metric() {
    local left=$((pending_deadline - SECONDS))
    [ "$left" -gt 0 ] || fail "the calls to the existing APIs did not finish within ${max_pending_wait}s"
    metrics_max_time=$((left < metrics_max_time ? left : metrics_max_time)) metric "$@"
}

# Difference between two metric values, or "-" if the metric does not exist.
delta() {
    if [ "$1" = "-" ] || [ "$2" = "-" ]; then
        echo "-"
    else
        awk -v after="$1" -v before="$2" 'BEGIN { print after - before }'
    fi
}

# Mean in ms of a timer during the run, from the deltas of its total time (in seconds) and
# its count.
mean_ms() {
    local total_time="$1" count="$2"
    if [ "$total_time" = "-" ] || [ "$count" = "-" ] || [ "$count" -eq 0 ]; then
        echo "-"
    else
        awk -v total_time="$total_time" -v count="$count" 'BEGIN { printf "%.1f ms\n", total_time * 1000 / count }'
    fi
}

influx() {
    (cd "$harness" && $compose exec -T influxdb influx -database k6 -format column -execute "$1")
}

# Requests without response (status 0) of this run, from InfluxDB.
no_response_count() {
    local count
    count=$(cd "$harness" && $compose exec -T influxdb influx -database k6 -format csv -execute \
        "SELECT count(value) FROM http_reqs WHERE run_id = '$run_id' AND status = '0'" | awk -F, 'NR == 2 { print $3 }') \
        || fail "could not count the requests without response in InfluxDB"
    echo "${count:-0}"
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
curl -sf --connect-timeout 2 --max-time "$metrics_max_time" "$app/actuator/health" > /dev/null \
    || fail "the application is not running on $app"
curl -sf --connect-timeout 2 --max-time "$metrics_max_time" "$app/actuator/metrics" > /dev/null \
    || fail "the metrics endpoint is not exposed on $app"

similar_ids_before=$(calls "$similar_ids_uri" COUNT)
similar_ids_time_before=$(calls "$similar_ids_uri" TOTAL_TIME)
details_before=$(calls "$detail_uri" COUNT)
details_time_before=$(calls "$detail_uri" TOTAL_TIME)
granted_before=$(metric catalog.calls.permit.wait COUNT outcome:acquired)
granted_wait_before=$(metric catalog.calls.permit.wait TOTAL_TIME outcome:acquired)
rejected_before=$(metric catalog.calls.permit.wait COUNT outcome:rejected)
out_of_budget_before=$(metric catalog.calls.permit.wait COUNT outcome:deadline)

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
echo "== Calls to the existing APIs still pending when k6 ended"
pending_start=$SECONDS
pending_deadline=$((pending_start + max_pending_wait))
# Versions before catalog.calls.pending only have catalog.calls.active
pending_gauge=catalog.calls.pending
pending=$(pending_metric "$pending_gauge" VALUE)
if [ "$pending" = "-" ]; then
    pending_gauge=catalog.calls.active
    pending=$(pending_metric "$pending_gauge" VALUE)
fi
if [ "$pending" = "-" ]; then
    echo "unknown: this version has no catalog.calls.pending or catalog.calls.active metric" \
        | tee "$out/pending.txt"
else
    # Idle: the same sample, with no call pending, several times in a row
    previous=""
    settled=1
    while [ "$settled" -lt "$settled_samples" ]; do
        sleep 1
        pending_now=$(pending_metric "$pending_gauge" VALUE)
        granted_now=$(pending_metric catalog.calls.permit.wait COUNT outcome:acquired)
        rejected_now=$(pending_metric catalog.calls.permit.wait COUNT outcome:rejected)
        sample="$pending_now $granted_now $rejected_now"
        if [ "$pending_now" = 0 ] && [ "$sample" = "$previous" ]; then
            settled=$((settled + 1))
        else
            settled=1
        fi
        previous="$sample"
    done
    echo "$pending ($pending_gauge), all finished after waiting $((SECONDS - pending_start))s" \
        | tee "$out/pending.txt"
fi

echo
echo "== Calls to the existing APIs during the run"
similar_ids_after=$(calls "$similar_ids_uri" COUNT)
similar_ids_time_after=$(calls "$similar_ids_uri" TOTAL_TIME)
details_after=$(calls "$detail_uri" COUNT)
details_time_after=$(calls "$detail_uri" TOTAL_TIME)
similar_ids=$(delta "$similar_ids_after" "$similar_ids_before")
similar_ids_time=$(delta "$similar_ids_time_after" "$similar_ids_time_before")
details=$(delta "$details_after" "$details_before")
details_time=$(delta "$details_time_after" "$details_time_before")
similar_ids_mean=$(mean_ms "$similar_ids_time" "$similar_ids")
details_mean=$(mean_ms "$details_time" "$details")
{
    # Mean duration as seen by the HTTP client, from sending the request to receiving the
    # response: it includes connections, transport and the mock server, not the wait for a
    # slot. No similarids response of the mocks has a delay.
    echo "similarids: $similar_ids calls, mean $similar_ids_mean"
    echo "details:    $details calls, mean $details_mean"
} | tee "$out/calls.txt"

echo
echo "== Concurrency limit of the calls to the existing APIs"
# The peak counts since startup, hence the restart before each measurement
peak=$(metric catalog.calls.active.peak VALUE)
granted_after=$(metric catalog.calls.permit.wait COUNT outcome:acquired)
granted_wait_after=$(metric catalog.calls.permit.wait TOTAL_TIME outcome:acquired)
rejected_after=$(metric catalog.calls.permit.wait COUNT outcome:rejected)
out_of_budget_after=$(metric catalog.calls.permit.wait COUNT outcome:deadline)
granted=$(delta "$granted_after" "$granted_before")
granted_wait=$(delta "$granted_wait_after" "$granted_wait_before")
mean_wait=$(mean_ms "$granted_wait" "$granted")
rejected=$(delta "$rejected_after" "$rejected_before")
out_of_budget=$(delta "$out_of_budget_after" "$out_of_budget_before")
{
    echo "most in progress at once: $peak"
    echo "slots granted:            $granted"
    echo "mean wait for a slot:     $mean_wait"
    echo "calls rejected:           $rejected"
    echo "out of budget waiting:    $out_of_budget"
} | tee "$out/limit.txt"

echo
echo "== Requests per scenario and status"
influx "SELECT count(value) AS requests FROM http_reqs WHERE run_id = '$run_id' GROUP BY scenario, status" \
    > "$out/status.txt"
[ -s "$out/status.txt" ] || fail "InfluxDB has no samples tagged run_id=$run_id"
cat "$out/status.txt"

echo
# A request without response (status 0) got no HTTP status from the application; k6 logs
# why it failed, for example that it could not connect or that it timed out.
# The number of warnings is checked against InfluxDB: a missing warning does not mean that
# there was no error.
echo "== Requests without response (status 0), by scenario and cause"
{ grep 'msg="Request Failed"' "$out/k6.txt" || true; } \
    | sed -E 's|.*/product/([0-9]+)/similar\\": (.*)"$|\1 \2|; s| [0-9.]+:[0-9]+: |: |' \
    | sed -E 's/^1 /normal: /; s/^4 /notFound: /; s/^5 /error: /; s/^2 /slow: /; s/^3 /verySlow: /' \
    | sort | uniq -c | sort -rn > "$out/no-response-causes.txt"
no_response=$(no_response_count)
with_cause=$(awk '{ total += $1 } END { print total + 0 }' "$out/no-response-causes.txt")
{
    cat "$out/no-response-causes.txt"
    echo "requests without response: $no_response, with a cause in the k6 warnings: $with_cause"
    if [ "$with_cause" -ne "$no_response" ]; then
        echo "WARNING: the k6 warnings do not account for every request without response"
    fi
} | tee "$out/no-response.txt"

echo
# Requests without response (status 0) have no real duration, so they are left out of the
# latency and only counted above.
echo "== Latency per scenario and status of the answered requests (ms)"
influx "SELECT percentile(value, 50) AS p50, percentile(value, 95) AS p95, max(value) AS max FROM http_req_duration WHERE run_id = '$run_id' AND status != '0' GROUP BY scenario, status" \
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
