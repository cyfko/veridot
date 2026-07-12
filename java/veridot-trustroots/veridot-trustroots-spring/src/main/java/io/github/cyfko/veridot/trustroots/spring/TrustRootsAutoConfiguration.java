package io.github.cyfko.veridot.trustroots.spring;

import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.trustroots.api.TrustRootProvider;
import io.github.cyfko.veridot.trustroots.core.CachingTrustRoot;
import io.github.cyfko.veridot.trustroots.taas.client.TaasTrustRootProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Paths;
import java.util.Collections;

/**
 * Auto-configuration Spring Boot pour enregistrer automatiquement le moteur de validation de clés Veridot
 * dans le contexte de l'application cliente.
 * <p>
 * S'active uniquement si la classe {@link TrustRoot} est présente dans le classpath.
 * Déclare de manière conditionnelle les Beans {@link TrustRootProvider} et {@link TrustRoot}.
 */
@AutoConfiguration
@ConditionalOnClass(TrustRoot.class)
@EnableConfigurationProperties(TrustRootsProperties.class)
public class TrustRootsAutoConfiguration {

    /**
     * Constructeur par défaut.
     */
    public TrustRootsAutoConfiguration() {
    }

    /**
     * Enregistre le fournisseur d'API distant TAAS.
     *
     * @param properties Propriétés de configuration injectées.
     * @return L'instance {@link TrustRootProvider} du client TAAS.
     */
    @Bean
    @ConditionalOnMissingBean(TrustRootProvider.class)
    public TrustRootProvider trustRootProvider(TrustRootsProperties properties) {
        if ("taas".equalsIgnoreCase(properties.getProviderType())) {
            return new TaasTrustRootProvider(
                properties.getTaasClusterUrls() != null ? properties.getTaasClusterUrls() : Collections.singletonList("http://127.0.0.1:8443"),
                null,
                properties.getConnectTimeout()
            );
        }
        throw new IllegalArgumentException("Unsupported veridot provider type: " + properties.getProviderType());
    }

    /**
     * Enregistre et initialise le moteur de cache de validation de clés de confiance {@link TrustRoot} (CachingTrustRoot).
     *
     * @param properties Propriétés de configuration.
     * @param provider Fournisseur d'API TAAS injecté.
     * @return L'instance {@link TrustRoot} initialisée.
     */
    @Bean(initMethod = "initialize", destroyMethod = "close")
    @ConditionalOnMissingBean(TrustRoot.class)
    public TrustRoot trustRoot(TrustRootsProperties properties, TrustRootProvider provider) {
        return CachingTrustRoot.builder()
                .provider(provider)
                .l2Directory(Paths.get(properties.getL2Directory()))
                .l1MaxSize(properties.getL1MaxSize())
                .refreshThreshold(properties.getRefreshThreshold())
                .staleWindow(properties.getStaleWindow())
                .fullSyncInterval(properties.getFullSyncInterval())
                .resolveWaitTimeout(properties.getResolveWaitTimeout())
                .build();
    }
}
