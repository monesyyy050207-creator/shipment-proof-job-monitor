package dev.infrai.logistics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class LogisticsJobMonitorTest {
    public static void main(String[] args) throws Exception {
        List<String> captures = new ArrayList<>();
        LogisticsJobMonitor monitor = new LogisticsJobMonitor(
                (exception, key) -> captures.add(exception + "|" + key));
        Instant completedAt = Instant.parse("2026-08-20T02:00:00Z");

        List<LogisticsJobMonitor.ShipmentEvent> events = List.of(
                new LogisticsJobMonitor.ShipmentEvent("SHP-MISSING", LogisticsJobMonitor.ShipmentState.DELIVERED, completedAt),
                new LogisticsJobMonitor.ShipmentEvent("SHP-SIGNED", LogisticsJobMonitor.ShipmentState.DELIVERED, completedAt),
                new LogisticsJobMonitor.ShipmentEvent("SHP-MOVING", LogisticsJobMonitor.ShipmentState.IN_TRANSIT, completedAt));
        List<LogisticsJobMonitor.ProofOfDeliveryFile> proofs = List.of(
                new LogisticsJobMonitor.ProofOfDeliveryFile("SHP-SIGNED", "proofs/SHP-SIGNED.pdf", "sha256:abc123"));

        LogisticsJobMonitor.JobOutcome outcome = monitor.reconcile(events, proofs);

        check(outcome.equals(new LogisticsJobMonitor.JobOutcome(3, 1)), "one delivered shipment must be captured");
        check(captures.size() == 1, "capture count");
        check(captures.get(0).contains("SHP-MISSING"), "capture identifies missing proof");
        check(!captures.get(0).contains("SHP-SIGNED"), "signed shipment remains clear");
        System.out.println("PASS: missing proof captured once; signed and in-transit shipments ignored");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
