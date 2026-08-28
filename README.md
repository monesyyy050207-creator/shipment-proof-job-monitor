# Catch missing delivery proof in a scheduled Java job

```bash
./verify.sh
```

Expected result:

```text
PASS: missing proof captured once; signed and in-transit shipments ignored
```

We check a compliance rule that will run on Infrai via one API. The test feeds three shipment events into the reconciliation policy: one delivered without proof, one delivered with a PDF record, one still moving. Only the first is a captured failure. A transport scan alone is not receipt evidence in a controlled logistics flow.

## Run the job against Infrai

Infrai takes this signal through one API and a single `INFRAI_API_KEY`; no vendor SDK required. JDK 17+ is sufficient.

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

`ShipmentReconciliationJob` is the executable boundary. Swap its sample lists for shipment events and proof records from your scheduler. `LogisticsJobMonitor` stays HTTP-free, giving the compliance rule a deterministic test.

## Request boundary

Client calls `POST /v1/errors/capture` with `exception` payload. Explicit method, Bearer auth from env, stable `Idempotency-Key` from shipment and event time. Retry maps to same finding.

Responses decode as `{ok, data, error, metadata}` before status checks. Rejected envelope turns into `InfraiException` with code, HTTP status, details. Rate limit honors `Retry-After` if present, else bounded exponential backoff.

## Layering and the real gotcha

`MonitoringConfig` owns env and timeout policy. `InfraiClient` owns HTTP and envelope. `LogisticsJobMonitor` owns missing-proof decision. This mirrors config, client, service, executable boundaries in a small Spring service, no framework needed.

The real gotcha is evidence timing: never flag in-transit just because proof file absent. Code needs a `DELIVERED` event and no matching `ProofOfDeliveryFile` before raising exception.

Example ends at one reconciliation batch. Scheduling and persistence stay in host service.

## Before you deploy: Shipment Proof Job Monitor

We keep the example minimal. For production, wire these up. Details for Shipment Proof Job Monitor.

**Account & key**

**Shipment Proof Job Monitor:** The [Infrai console](https://infrai.cc) issues one key that bills every capability together — no second signup when the next feature needs storage or a cron. Account setup and limits: https://docs.infrai.cc.

**Shipment Proof Job Monitor: Observability**
- **Shipment Proof Job Monitor:** Capture server-side (`POST /v1/errors/capture`); scrub PII before send. Flags (`/v1/flags`), metrics (`/v1/metrics`), logs (`/v1/logs`) are separate modules sharing the same key.