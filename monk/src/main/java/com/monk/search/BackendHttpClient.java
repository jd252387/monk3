package com.monk.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.monk.mapping.BackendHttpConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.faulttolerance.Retry;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * POSTs a translated query body to a single search backend. Extracted from
 * {@link SearchExecutionService} into its own bean so the fault-tolerance and Micrometer
 * interceptors actually fire: they only apply on cross-bean CDI calls, and the fan-out invokes
 * {@code searchBackend} via a self-lambda, which would leave annotations there inert.
 */
@ApplicationScoped
public class BackendHttpClient {
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public BackendHttpClient(ObjectMapper objectMapper, MeterRegistry meterRegistry, BackendHttpConfig config) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.httpClient = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
        this.requestTimeout = config.requestTimeout();
    }

    /**
     * Executes one backend request and returns the parsed response, timed into
     * {@code monk.backend.request} (tags {@code backend}/{@code engine}/{@code outcome}). Retried on
     * failure; a request that still fails throws, and the caller drops this backend from the fan-out.
     */
    // ponytail: retries every backend failure including deterministic 4xx; add abortOn or a dedicated
    // transient exception type if bad-request retry amplification ever matters.
    @Retry(maxRetries = 2, delay = 200, retryOn = SearchExecutionException.class)
    public JsonNode post(String backendName, String engine, URI uri, JsonNode body) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .header(CONTENT_TYPE, APPLICATION_JSON)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                outcome = "failure";
                throw new SearchExecutionException(
                        "Search backend '" + backendName + "' returned HTTP " + response.statusCode() + " - " + new String(response.body()));
            }
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            outcome = "failure";
            throw new SearchExecutionException("Search backend '" + backendName + "' returned invalid JSON", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            outcome = "failure";
            throw new SearchExecutionException("Search backend '" + backendName + "' request was interrupted", exception);
        } finally {
            sample.stop(meterRegistry.timer("monk.backend.request",
                    "backend", backendName, "engine", engine, "outcome", outcome));
        }
    }
}
