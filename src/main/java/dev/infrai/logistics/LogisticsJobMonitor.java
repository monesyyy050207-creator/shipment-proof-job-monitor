package dev.infrai.logistics;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Business policy for the scheduled proof-of-delivery reconciliation. */
public final class LogisticsJobMonitor {
    public enum ShipmentState { CREATED, IN_TRANSIT, DELIVERED }

    public record ShipmentEvent(String shipmentId, ShipmentState state, Instant occurredAt) {
        public ShipmentEvent {
            Objects.requireNonNull(shipmentId);
            Objects.requireNonNull(state);
            Objects.requireNonNull(occurredAt);
        }
    }

    public record ProofOfDeliveryFile(String shipmentId, String objectKey, String sha256) { }

    public record JobOutcome(int examined, int capturedFailures) { }

    @FunctionalInterface
    public interface FailureSink {
        void capture(String exception, String idempotencyKey) throws IOException, InterruptedException;
    }

    private final FailureSink failureSink;

    public LogisticsJobMonitor(FailureSink failureSink) {
        this.failureSink = failureSink;
    }

    public JobOutcome reconcile(List<ShipmentEvent> events, List<ProofOfDeliveryFile> proofs)
            throws IOException, InterruptedException {
        int captured = 0;
        for (ShipmentEvent event : events) {
            boolean proofPresent = proofs.stream().anyMatch(proof -> proof.shipmentId().equals(event.shipmentId()));
            if (event.state() == ShipmentState.DELIVERED && !proofPresent) {
                String exception = "MissingProofOfDelivery{shipmentId=" + event.shipmentId()
                        + ", occurredAt=" + event.occurredAt() + "}";
                failureSink.capture(exception, "pod-reconciliation:" + event.shipmentId() + ":" + event.occurredAt());
                captured++;
            }
        }
        return new JobOutcome(events.size(), captured);
    }
}
