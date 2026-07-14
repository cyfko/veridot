package io.github.cyfko.veridot.trustroots.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Spring Boot configuration properties prefixed with {@code veridot.trustroots}.
 * Allows configuring the various aspects of the trust root cache management and the TAAS client.
 */
@ConfigurationProperties(prefix = "veridot.trustroots")
public class TrustRootsProperties {

    /** Maximum number of keys stored concurrently in memory in the L1 cache. Default value: 10000. */
    private int l1MaxSize = 10000;
    
    /** Physical directory used by RocksDB for the local L2 cache. Default value: {@code ~/.veridot/trust-cache}. */
    private String l2Directory = System.getProperty("user.home") + "/.veridot/trust-cache";
    
    /** Threshold before nominal expiration to trigger an asynchronous refresh (L1/L2). Default value: 1 hour. */
    private Duration refreshThreshold = Duration.ofHours(1);
    
    /** Fallback transient validity window (Stale Window). Default value: 5 minutes. */
    private Duration staleWindow = Duration.ofMinutes(5);
    
    /** Time interval to execute the incremental background sync recurring task. Default value: 6 hours. */
    private Duration fullSyncInterval = Duration.ofHours(6);
    
    /** Maximum tolerated delay during a concurrent resolution attempt on cache startup. Default value: 5 seconds. */
    private Duration resolveWaitTimeout = Duration.ofSeconds(5);
    
    /** Type of registry provider used. Currently only the replicated authority {@code "taas"} is supported. */
    private String providerType = "taas";
    


    /**
     * Default constructor.
     */
    public TrustRootsProperties() {
    }

    /**
     * @return the maximum size of the L1 cache
     */
    public int getL1MaxSize() { return l1MaxSize; }

    /**
     * @param l1MaxSize the maximum size of the L1 cache
     */
    public void setL1MaxSize(int l1MaxSize) { this.l1MaxSize = l1MaxSize; }

    /**
     * @return the L2 cache directory
     */
    public String getL2Directory() { return l2Directory; }

    /**
     * @param l2Directory the L2 cache directory
     */
    public void setL2Directory(String l2Directory) { this.l2Directory = l2Directory; }

    /**
     * @return the refresh threshold
     */
    public Duration getRefreshThreshold() { return refreshThreshold; }

    /**
     * @param refreshThreshold the refresh threshold
     */
    public void setRefreshThreshold(Duration refreshThreshold) { this.refreshThreshold = refreshThreshold; }

    /**
     * @return the stale tolerance window
     */
    public Duration getStaleWindow() { return staleWindow; }

    /**
     * @param staleWindow the stale tolerance window
     */
    public void setStaleWindow(Duration staleWindow) { this.staleWindow = staleWindow; }

    /**
     * @return the full sync interval
     */
    public Duration getFullSyncInterval() { return fullSyncInterval; }

    /**
     * @param fullSyncInterval the full sync interval
     */
    public void setFullSyncInterval(Duration fullSyncInterval) { this.fullSyncInterval = fullSyncInterval; }

    /**
     * @return the maximum wait timeout during resolution
     */
    public Duration getResolveWaitTimeout() { return resolveWaitTimeout; }

    /**
     * @param resolveWaitTimeout the maximum wait timeout during resolution
     */
    public void setResolveWaitTimeout(Duration resolveWaitTimeout) { this.resolveWaitTimeout = resolveWaitTimeout; }

    /**
     * @return the provider type (e.g., taas)
     */
    public String getProviderType() { return providerType; }

    /**
     * @param providerType the provider type
     */
    public void setProviderType(String providerType) { this.providerType = providerType; }


}
