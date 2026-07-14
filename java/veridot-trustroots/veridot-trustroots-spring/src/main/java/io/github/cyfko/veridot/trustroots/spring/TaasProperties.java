package io.github.cyfko.veridot.trustroots.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import java.util.List;

/**
 * Spring Boot configuration properties prefixed with {@code veridot.taas}.
 * Allows configuring the TAAS (Trust Authority as a Service) client.
 */
@ConfigurationProperties(prefix = "veridot.taas")
public class TaasProperties {

    /** List of base URLs of the TAAS cluster nodes (e.g., http://127.0.0.1:8080). */
    private List<String> endpoints;

    /** Connection and read timeout for querying TAAS. Default value: 3 seconds. */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /**
     * Default constructor.
     */
    public TaasProperties() {
    }

    /**
     * Returns the list of cluster endpoints.
     * @return list of URIs
     */
    public List<String> getEndpoints() {
        return endpoints;
    }

    /**
     * Sets the list of cluster endpoints.
     * @param endpoints list of URIs
     */
    public void setEndpoints(List<String> endpoints) {
        this.endpoints = endpoints;
    }

    /**
     * Returns the connection timeout.
     * @return the timeout duration
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * Sets the connection timeout.
     * @param connectTimeout the timeout duration
     */
    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }
}
