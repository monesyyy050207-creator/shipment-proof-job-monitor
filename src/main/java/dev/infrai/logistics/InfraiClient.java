package dev.infrai.logistics;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thin REST client. Every response is decoded as an Infrai envelope. */
public final class InfraiClient {
    private final MonitoringConfig config;
    private final HttpClient http;
    public final Errors errors = new Errors();

    public InfraiClient(MonitoringConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(config.requestTimeout()).build());
    }

    InfraiClient(MonitoringConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    public final class Errors {
        // Call sites use the stable idiom: infrai.errors.capture(exception, idempotencyKey).
        public Map<String, Object> capture(String exception, String idempotencyKey)
                throws IOException, InterruptedException {
            return call("POST", "/v1/errors/capture", Map.of("exception", exception), idempotencyKey);
        }
    }

    private Map<String, Object> call(String method, String path, Map<String, Object> payload,
                                     String idempotencyKey) throws IOException, InterruptedException {
        String body = Json.write(payload);
        for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
            HttpRequest request = HttpRequest.newBuilder(config.baseUri().resolve(path))
                    .timeout(config.requestTimeout())
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", idempotencyKey)
                    .method(method, HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            Map<String, Object> envelope = Json.object(response.body());
            if (response.statusCode() == 429 && attempt < config.maxAttempts()) {
                Thread.sleep(retryDelay(response, attempt).toMillis());
                continue;
            }
            Object ok = envelope.get("ok");
            if (!Boolean.TRUE.equals(ok)) {
                Map<String, Object> error = asObject(envelope.get("error"));
                throw new InfraiException(
                        String.valueOf(error.getOrDefault("code", "UNKNOWN")),
                        response.statusCode(), error);
            }
            if (response.statusCode() >= 500) {
                throw new IOException("Infrai transport status " + response.statusCode());
            }
            return asObject(envelope.get("data"));
        }
        throw new IOException("Retry budget exhausted");
    }

    private static Duration retryDelay(HttpResponse<?> response, int attempt) {
        String value = response.headers().firstValue("Retry-After").orElse("").trim();
        try {
            long seconds = Long.parseLong(value);
            return Duration.ofSeconds(Math.max(0, seconds));
        } catch (NumberFormatException ignored) {
            return Duration.ofMillis(250L * (1L << (attempt - 1)));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value) {
        if (value == null) return Map.of();
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new IllegalArgumentException("Expected JSON object");
    }

    public static final class InfraiException extends IOException {
        private final String code;
        private final int status;
        private final Map<String, Object> details;

        InfraiException(String code, int status, Map<String, Object> details) {
            super("Infrai rejected request: " + code);
            this.code = code;
            this.status = status;
            this.details = Map.copyOf(details);
        }

        public String code() { return code; }
        public int status() { return status; }
        public Map<String, Object> details() { return details; }
    }

    /** Small JSON codec for the envelope and this example's string-only request. */
    static final class Json {
        static String write(Object value) {
            if (value == null) return "null";
            if (value instanceof String text) return quote(text);
            if (value instanceof Boolean || value instanceof Number) return value.toString();
            if (value instanceof Map<?, ?> map) {
                StringBuilder out = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    out.append(quote(String.valueOf(entry.getKey()))).append(':').append(write(entry.getValue()));
                }
                return out.append('}').toString();
            }
            throw new IllegalArgumentException("Unsupported JSON value");
        }

        static Map<String, Object> object(String source) throws IOException {
            Object value = new Parser(source).parseDocument();
            if (!(value instanceof Map<?, ?>)) throw new IOException("Envelope must be a JSON object");
            return asObject(value);
        }

        private static String quote(String value) {
            StringBuilder out = new StringBuilder("\"");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '\"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\b' -> out.append("\\b");
                    case '\f' -> out.append("\\f");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                        else out.append(c);
                    }
                }
            }
            return out.append('\"').toString();
        }

        private static final class Parser {
            private final String text;
            private int index;

            Parser(String text) { this.text = text; }

            Object parseDocument() throws IOException {
                Object value = value();
                whitespace();
                if (index != text.length()) fail("Trailing JSON content");
                return value;
            }

            private Object value() throws IOException {
                whitespace();
                if (index >= text.length()) return fail("Missing JSON value");
                return switch (text.charAt(index)) {
                    case '{' -> objectValue();
                    case '[' -> arrayValue();
                    case '\"' -> stringValue();
                    case 't' -> literal("true", true);
                    case 'f' -> literal("false", false);
                    case 'n' -> literal("null", null);
                    default -> numberValue();
                };
            }

            private Map<String, Object> objectValue() throws IOException {
                index++;
                Map<String, Object> result = new LinkedHashMap<>();
                whitespace();
                if (take('}')) return result;
                do {
                    whitespace();
                    String key = stringValue();
                    whitespace();
                    require(':');
                    result.put(key, value());
                    whitespace();
                } while (take(','));
                require('}');
                return result;
            }

            private List<Object> arrayValue() throws IOException {
                index++;
                List<Object> result = new ArrayList<>();
                whitespace();
                if (take(']')) return result;
                do {
                    result.add(value());
                    whitespace();
                } while (take(','));
                require(']');
                return result;
            }

            private String stringValue() throws IOException {
                require('\"');
                StringBuilder result = new StringBuilder();
                while (index < text.length()) {
                    char c = text.charAt(index++);
                    if (c == '\"') return result.toString();
                    if (c != '\\') { result.append(c); continue; }
                    if (index >= text.length()) return fail("Bad JSON escape");
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '\"', '\\', '/' -> result.append(escaped);
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> result.append((char) Integer.parseInt(read(4), 16));
                        default -> fail("Bad JSON escape");
                    }
                }
                return fail("Unclosed JSON string");
            }

            private Object numberValue() throws IOException {
                int start = index;
                while (index < text.length() && "-+0123456789.eE".indexOf(text.charAt(index)) >= 0) index++;
                if (start == index) return fail("Invalid JSON value");
                String number = text.substring(start, index);
                try { return number.contains(".") || number.contains("e") || number.contains("E")
                        ? Double.valueOf(number) : Long.valueOf(number); }
                catch (NumberFormatException e) { return fail("Invalid JSON number"); }
            }

            private Object literal(String expected, Object value) throws IOException {
                if (!read(expected.length()).equals(expected)) return fail("Invalid JSON literal");
                return value;
            }

            private String read(int count) throws IOException {
                if (index + count > text.length()) return fail("Unexpected end of JSON");
                String result = text.substring(index, index + count);
                index += count;
                return result;
            }

            private void whitespace() {
                while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
            }

            private boolean take(char expected) {
                if (index < text.length() && text.charAt(index) == expected) { index++; return true; }
                return false;
            }

            private void require(char expected) throws IOException {
                if (!take(expected)) fail("Expected '" + expected + "'");
            }

            private <T> T fail(String message) throws IOException {
                throw new IOException(message + " at character " + index);
            }
        }
    }
}
