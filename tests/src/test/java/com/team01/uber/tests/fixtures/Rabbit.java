package com.team01.uber.tests.fixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * RabbitMQ state-verification via the management HTTP API (port 15672 in docker-compose).
 *
 * <p>Endpoints used:
 * <ul>
 *   <li>{@code GET /api/queues/<vhost>/<name>} — queue depth, consumer count, message rates.</li>
 *   <li>{@code GET /api/exchanges/<vhost>/<name>} — publish-in/out rates.</li>
 * </ul>
 *
 * <p>Override via {@code -Drabbit.mgmt.url=http://host:port}, {@code -Drabbit.user}, {@code -Drabbit.pass}.
 */
public final class Rabbit {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_BASE = "http://localhost:15672";
    private static final String DEFAULT_VHOST = "%2F";

    private Rabbit() {}

    private static String baseUrl() {
        return System.getProperty("rabbit.mgmt.url", DEFAULT_BASE);
    }

    private static String authHeader() {
        String user = System.getProperty("rabbit.user", "guest");
        String pass = System.getProperty("rabbit.pass", "guest");
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode get(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Authorization", authHeader())
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("Rabbit mgmt GET " + path + " → " + response.statusCode() + ": " + response.body());
            }
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public static long queueDepth(String queueName) {
        return get("/api/queues/" + DEFAULT_VHOST + "/" + queueName).path("messages").asLong(-1);
    }

    public static long dlqDepth(String baseQueueName) {
        return queueDepth(baseQueueName + ".dlq");
    }

    public static long consumerCount(String queueName) {
        return get("/api/queues/" + DEFAULT_VHOST + "/" + queueName).path("consumers").asLong(-1);
    }

    public static long publishedTotal(String exchangeName) {
        return get("/api/exchanges/" + DEFAULT_VHOST + "/" + exchangeName)
                .path("message_stats").path("publish_in").asLong(0);
    }

    public static boolean queueExists(String queueName) {
        try {
            return get("/api/queues/" + DEFAULT_VHOST + "/" + queueName).has("name");
        } catch (RuntimeException e) {
            return false;
        }
    }
}
