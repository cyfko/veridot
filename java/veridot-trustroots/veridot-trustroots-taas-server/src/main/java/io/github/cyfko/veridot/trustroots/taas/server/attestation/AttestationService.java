package io.github.cyfko.veridot.trustroots.taas.server.attestation;

import io.github.cyfko.veridot.trustroots.api.spi.AttestationContext;
import io.github.cyfko.veridot.trustroots.api.spi.AttestationPlugin;
import io.github.cyfko.veridot.trustroots.api.spi.AttestationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Service that manages and delegates to Pluggable Attestation plugins.
 * Uses Java ServiceLoader to dynamically load plugins from the classpath.
 */
public class AttestationService {
    private static final Logger log = LoggerFactory.getLogger(AttestationService.class);
    private final Map<String, AttestationPlugin> plugins = new HashMap<>();

    public AttestationService() {
        ServiceLoader<AttestationPlugin> loader = ServiceLoader.load(AttestationPlugin.class);
        for (AttestationPlugin plugin : loader) {
            log.info("Loaded attestation plugin: {}", plugin.getPluginId());
            plugins.put(plugin.getPluginId(), plugin);
        }
    }

    /**
     * Verifies the proof using the requested plugin.
     *
     * @param pluginId The requested plugin ID (e.g. "kubernetes", "tpm")
     * @param proof The attestation proof bytes
     * @param ctx The context containing the requested CN and public key
     * @return The result of the attestation
     */
    public AttestationResult verify(String pluginId, byte[] proof, AttestationContext ctx) {
        AttestationPlugin plugin = plugins.get(pluginId);
        if (plugin == null) {
            log.warn("Requested attestation plugin '{}' is not registered", pluginId);
            return AttestationResult.rejected("Unsupported attestation plugin: " + pluginId);
        }
        try {
            return plugin.verify(proof, ctx);
        } catch (Exception e) {
            log.error("Plugin {} threw exception during verification", pluginId, e);
            return AttestationResult.rejected("Plugin internal error: " + e.getMessage());
        }
    }
}
