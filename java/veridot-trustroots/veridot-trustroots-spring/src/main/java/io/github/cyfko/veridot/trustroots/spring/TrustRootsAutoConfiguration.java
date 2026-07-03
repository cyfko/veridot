package io.github.cyfko.veridot.trustroots.spring;

import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.trustroots.api.TrustRootProvider;
import io.github.cyfko.veridot.trustroots.core.CachingTrustRoot;
import io.github.cyfko.veridot.trustroots.tad.client.TadTrustRootProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Paths;
import java.util.Collections;

@AutoConfiguration
@ConditionalOnClass(TrustRoot.class)
@EnableConfigurationProperties(TrustRootsProperties.class)
public class TrustRootsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TrustRootProvider.class)
    public TrustRootProvider trustRootProvider(TrustRootsProperties properties) {
        if ("tad".equalsIgnoreCase(properties.getProviderType())) {
            return new TadTrustRootProvider(
                properties.getTadClusterUrls() != null ? properties.getTadClusterUrls() : Collections.singletonList("http://127.0.0.1:8443"),
                null,
                properties.getConnectTimeout()
            );
        }
        throw new IllegalArgumentException("Unsupported veridot provider type: " + properties.getProviderType());
    }

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
