package dev.infrai.logistics;

import java.time.Instant;
import java.util.List;

/** Executable scheduled-job body; wire this main class to the scheduler used by the service. */
public final class ShipmentReconciliationJob {
    private ShipmentReconciliationJob() { }

    public static void main(String[] args) throws Exception {
        MonitoringConfig config = MonitoringConfig.fromEnvironment();
        InfraiClient infrai = new InfraiClient(config);
        LogisticsJobMonitor monitor = new LogisticsJobMonitor(
                (exception, key) -> infrai.errors.capture(exception, key));

        LogisticsJobMonitor.ShipmentEvent delivered = new LogisticsJobMonitor.ShipmentEvent(
                "SHP-1042", LogisticsJobMonitor.ShipmentState.DELIVERED, Instant.parse("2026-08-20T02:00:00Z"));
        LogisticsJobMonitor.JobOutcome outcome = monitor.reconcile(List.of(delivered), List.of());
        System.out.printf("examined=%d capturedFailures=%d%n", outcome.examined(), outcome.capturedFailures());
    }
}
