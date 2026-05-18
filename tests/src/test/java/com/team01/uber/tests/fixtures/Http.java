package com.team01.uber.tests.fixtures;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal HTTP test helper built on {@link java.net.http.HttpClient}. Returned {@link Response}
 * exposes status + raw body + parsed JSON via a small fluent API.
 */
public final class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Http() {}

    public static Builder request(String baseUri, String path) {
        return new Builder(baseUri, path);
    }

    public static final class Builder {
        private final String baseUri;
        private final String path;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String method = "GET";
        private String body;

        Builder(String baseUri, String path) {
            this.baseUri = baseUri;
            this.path = path;
            this.headers.put("Accept", "application/json");
        }

        public Builder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        public Builder bearer(String token) {
            return header("Authorization", "Bearer " + token);
        }

        public Builder json(Object body) {
            try {
                this.body = MAPPER.writeValueAsString(body);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            this.headers.put("Content-Type", "application/json");
            return this;
        }

        public Response get() {
            this.method = "GET";
            return send();
        }

        public Response post() {
            this.method = "POST";
            return send();
        }

        public Response put() {
            this.method = "PUT";
            return send();
        }

        public Response delete() {
            this.method = "DELETE";
            return send();
        }

        private Response send() {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(baseUri + path))
                    .timeout(Duration.ofSeconds(10));
            headers.forEach(b::header);
            HttpRequest.BodyPublisher publisher = body == null
                    ? BodyPublishers.noBody()
                    : BodyPublishers.ofString(body);
            switch (method) {
                case "GET"    -> b.GET();
                case "POST"   -> b.POST(publisher);
                case "PUT"    -> b.PUT(publisher);
                case "DELETE" -> b.DELETE();
                default       -> throw new IllegalStateException("Unsupported method: " + method);
            }
            try {
                HttpResponse<String> response = CLIENT.send(b.build(), BodyHandlers.ofString());
                return new Response(response.statusCode(), response.body());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("HTTP " + method + " " + baseUri + path + " failed", e);
            }
        }
    }

    public static final class Response {
        private final int status;
        private final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }

        public int status() {
            return status;
        }

        public String body() {
            return body;
        }

        public JsonNode json() {
            try {
                return MAPPER.readTree(body == null ? "{}" : body);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public <T> T as(Class<T> type) {
            try {
                return MAPPER.readValue(body, type);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public <T> T as(TypeReference<T> type) {
            try {
                return MAPPER.readValue(body, type);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
