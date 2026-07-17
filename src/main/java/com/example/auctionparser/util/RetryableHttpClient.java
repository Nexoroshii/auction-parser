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
        // Persist cookies across requests so an anti-bot (Incapsula) clearance
        // cookie set on the first hit is reused by the rest — without it a burst
        // of cookieless requests (e.g. IAAI series expansion) gets challenged.
        java.net.CookieManager cookieManager = new java.net.CookieManager();
        cookieManager.setCookiePolicy(java.net.CookiePolicy.ACCEPT_ALL);
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(cookieManager);
        applyProxy(builder, properties.getProxy());
        this.client = builder.build();
    }

    /**
     * Routes HTTP traffic through the configured proxy so the auction sites see a
     * US IP without a system VPN. The JDK HTTP client supports HTTP proxies only;
     * a SOCKS scheme is skipped here (it still applies to the Copart browser).
     */
    private void applyProxy(HttpClient.Builder builder, AppProperties.Proxy proxy) {
        if (proxy == null || !proxy.isUsable()) {
            return;
        }
        if (proxy.getScheme() != null && proxy.getScheme().toLowerCase().startsWith("socks")) {
            log.warn("Proxy scheme '{}' is not supported by the HTTP client (IAAI/Telegram); "
                    + "use an http proxy for those. Copart still uses it.", proxy.getScheme());
            return;
        }
        builder.proxy(java.net.ProxySelector.of(
                new java.net.InetSocketAddress(proxy.getHost(), proxy.getPort())));
        if (proxy.getUsername() != null && !proxy.getUsername().isBlank()) {
            // Allow Basic auth on the CONNECT tunnel (disabled by default for HTTPS).
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
            String user = proxy.getUsername();
            char[] pass = proxy.getPassword() == null ? new char[0] : proxy.getPassword().toCharArray();
            builder.authenticator(new java.net.Authenticator() {
                @Override
                protected java.net.PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() == RequestorType.PROXY) {
                        return new java.net.PasswordAuthentication(user, pass);
                    }
                    return null;
                }
            });
        }
        log.info("HTTP client routing through proxy {}:{}", proxy.getHost(), proxy.getPort());
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
