package io.github.cyfko.veridot.trustroots.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import java.util.List;

/**
 * Propriétés de configuration Spring Boot préfixées par {@code veridot.taas}.
 * Permet de configurer le client TAAS (Trust Authority as a Service).
 */
@ConfigurationProperties(prefix = "veridot.taas")
public class TaasProperties {

    /** Liste des adresses de base des nœuds du cluster TAAS (ex: http://127.0.0.1:8080). */
    private List<String> endpoints;

    /** Timeout de connexion et de lecture réseau pour interroger le TAAS. Valeur par défaut : 3 secondes. */
    private Duration connectTimeout = Duration.ofSeconds(3);

    public TaasProperties() {
    }

    public List<String> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(List<String> endpoints) {
        this.endpoints = endpoints;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }
}
