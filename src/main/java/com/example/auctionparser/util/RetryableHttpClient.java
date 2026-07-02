package com.example.auctionparser.util;

import com.example.auctionparser.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin wrapper over the JDK {@link HttpClient} that adds automatic retries with
 * exponential backoff for transient failures (IO errors, HTTP 429 and 5xx).
 * Shared by the Telegram client and the auction parsers.
 */
@Component
public class RetryableHttpClient {

    private static final Logger log = LoggerFactory.getLogger(RetryableHttpClient.class);

    private final HttpClient client;
    private final AppProperties.Http config;

    public RetryableHttpClient(AppProperties properties) {
        this.config = properties.getHttp();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public HttpResponse<String> sendForString(HttpRequest request) throws IOException {
        return send(request, HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<byte[]> sendForBytes(HttpRequest request) throws IOException {
        return send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    /**
     * Sends a request, retrying transient failures up to {@code maxRetries}
     * times. Returns the last response even if it is a non-transient error so
     * the caller can inspect the status code.
     *
     * @throws IOException if all attempts fail with an IO error.
     */
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        IOException lastIo = null;
        for (int attempt = 1; attempt <= config.getMaxRetries(); attempt++) {
            try {
                HttpResponse<T> response = client.send(request, handler);
                if (isTransient(response.statusCode()) && attempt < config.getMaxRetries()) {
                    log.warn("Transient HTTP {} from {} (attempt {}/{}), retrying",
                            response.statusCode(), request.uri(), attempt, config.getMaxRetries());
                    backoff(attempt);
                    continue;
                }
                return response;
            } catch (IOException e) {
                lastIo = e;
                log.warn("HTTP call to {} failed (attempt {}/{}): {}",
                        request.uri(), attempt, config.getMaxRetries(), e.toString());
                if (attempt < config.getMaxRetries()) {
                    backoff(attempt);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during HTTP call to " + request.uri(), e);
            }
        }
        throw lastIo != null ? lastIo
                : new IOException("HTTP call to " + request.uri() + " failed after retries");
    }

    /** Convenience builder pre-populated with shared request timeout. */
    public HttpRequest.Builder requestBuilder(String uri) {
        return HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()));
    }

    private boolean isTransient(int status) {
        return status == 429 || status >= 500;
    }

    private void backoff(int attempt) {
        long delay = config.getRetryBackoffMillis() * (1L << (attempt - 1)); // exponential
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
