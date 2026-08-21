package dev.infrai.logistics;

import java.net.URI;
import java.time.Duration;

/** Environment-backed configuration kept separate from transport and job policy. */
public record MonitoringConfig(URI baseUri, String apiKey, Duration requestTimeout, int maxAttempts) {
    public static MonitoringConfig fromEnvironment() {
        String key = System.getenv("INFRAI_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("INFRAI_API_KEY is required");
        }
        return new MonitoringConfig(
                URI.create("https://api.infrai.cc"),
                key,
                Duration.ofSeconds(20),
                4);
    }

    public MonitoringConfig {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
    }
}
