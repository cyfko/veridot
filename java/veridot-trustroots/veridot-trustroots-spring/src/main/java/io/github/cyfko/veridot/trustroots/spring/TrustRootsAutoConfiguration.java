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
 * Spring Boot auto-configuration to automatically register the Veridot key validation engine
 * into the client application's context.
 * <p>
 * Activates only if the {@link TrustRoot} class is present in the classpath.
 * Conditionally declares the {@link TrustRootProvider} and {@link TrustRoot} Beans.
 */
@AutoConfiguration
@ConditionalOnClass(TrustRoot.class)
@EnableConfigurationProperties({TrustRootsProperties.class, TaasProperties.class})
public class TrustRootsAutoConfiguration {

    /**
     * Default constructor.
     */
    public TrustRootsAutoConfiguration() {
    }

    /**
     * Registers the remote TAAS API provider.
     *
     * @param properties Injected configuration properties for the cache.
     * @param taasProperties Configuration properties for the TAAS network endpoints.
     * @return The {@link TrustRootProvider} instance of the TAAS client.
     */
    @Bean
    @ConditionalOnMissingBean(TrustRootProvider.class)
    public TrustRootProvider trustRootProvider(TrustRootsProperties properties, TaasProperties taasProperties) {
        if ("taas".equalsIgnoreCase(properties.getProviderType())) {
            return new TaasTrustRootProvider(
                taasProperties.getEndpoints() != null && !taasProperties.getEndpoints().isEmpty() ? taasProperties.getEndpoints() : Collections.singletonList("http://127.0.0.1:8443"),
                null,
                taasProperties.getConnectTimeout()
            );
        }
        throw new IllegalArgumentException("Unsupported veridot provider type: " + properties.getProviderType());
    }

    /**
     * Registers and initializes the trust key validation cache engine {@link TrustRoot} (CachingTrustRoot).
     *
     * @param properties Configuration properties.
     * @param provider Injected TAAS API provider.
     * @return The initialized {@link TrustRoot} instance.
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
