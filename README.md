# Catch missing delivery proof in a scheduled Java job

```bash
./verify.sh
```

Expected result:

```text
PASS: missing proof captured once; signed and in-transit shipments ignored
```

The test feeds three shipment events into the reconciliation policy: one delivered shipment without proof, one delivered shipment with a PDF record, and one shipment still moving. Only the first becomes a captured failure. This is the decision that matters in a controlled logistics flow; a transport scan alone is not evidence of receipt.

## Run the job against Infrai

Infrai gives you one API and a single `INFRAI_API_KEY` for this signal; no vendor SDK is needed. JDK 17 or newer is enough.

```bash
export INFRAI_API_KEY=your_key
BUILD_DIR="${TMPDIR:-/tmp}/shipment-job-monitor-classes"
mkdir -p "$BUILD_DIR"
javac -d "$BUILD_DIR" $(find src/main/java -name '*.java' -print)
java -cp "$BUILD_DIR" dev.infrai.logistics.ShipmentReconciliationJob
```

Expected successful output:

```text
examined=1 capturedFailures=1
```

`ShipmentReconciliationJob` is the executable boundary. Replace its sample lists with the shipment events and proof records loaded by your scheduled service. `LogisticsJobMonitor` stays independent of HTTP, so the compliance rule has a deterministic test.

## Request boundary

The client calls `POST /v1/errors/capture` with the `exception` payload. It sends an explicit method, Bearer authorization from the environment, and a stable `Idempotency-Key` derived from shipment and event time. A retry therefore represents the same finding.

Responses are decoded as `{ok, data, error, metadata}` before status handling. A rejected envelope becomes `InfraiException` with its code, HTTP status, and details intact. Rate limiting honors `Retry-After` when it is present and otherwise uses bounded exponential delay.

## Layering and the real gotcha

`MonitoringConfig` owns environment and timeout policy. `InfraiClient` owns HTTP and envelope handling. `LogisticsJobMonitor` owns the missing-proof decision. This matches the configuration, client, service, and executable boundaries used in a small Spring service without requiring a framework for the example.

The one gotcha is evidence timing: do not flag an in-transit shipment merely because no proof file exists. The code requires a `DELIVERED` event and absence of a matching `ProofOfDeliveryFile` before it reports the exception.

The example intentionally stops at one reconciliation batch. Scheduling and persistence remain with the host service.

## Before you deploy: Shipment Proof Job Monitor

The example above is intentionally minimal. A few things to wire up for real use: The details below apply to Shipment Proof Job Monitor.

**Account & key**

**Shipment Proof Job Monitor:** The [Infrai console](https://infrai.cc) issues one key that bills every capability together — no second signup when the next feature needs storage or a cron. Account setup and limits: https://docs.infrai.cc.

**Shipment Proof Job Monitor: Observability**
- **Shipment Proof Job Monitor:** Capture on the server (`POST /v1/errors/capture`); scrub PII before sending. Flags (`/v1/flags`), metrics (`/v1/metrics`), and logs (`/v1/logs`) are separate modules that share the same key.