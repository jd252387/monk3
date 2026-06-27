package com.monk3.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * POSTs a JSON body to an HTTP endpoint and parses the JSON response. Centralizes the request
 * building, the {@code application/json} headers, the 2xx status check, and the
 * {@code IOException}/{@code InterruptedException} handling shared by the search and embedding
 * clients. Each caller supplies an {@link Errors} that maps the three failure modes onto its own
 * domain exception, keeping caller-specific messages intact.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class JsonHttpClient {
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public JsonNode post(URI uri, JsonNode body, Errors errors) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header(CONTENT_TYPE, APPLICATION_JSON)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw errors.status(response.statusCode(), new String(response.body()));
            }
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw errors.io(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw errors.interrupted(exception);
        }
    }

    /** Maps each failure mode of a JSON POST onto the caller's own domain exception. */
    public interface Errors {
        RuntimeException status(int statusCode, String body);

        RuntimeException io(IOException cause);

        RuntimeException interrupted(InterruptedException cause);
    }
}
