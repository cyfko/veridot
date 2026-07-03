package io.github.cyfko.veridot.trustroots.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "veridot.trustroots")
public class TrustRootsProperties {

    private int l1MaxSize = 10000;
    private String l2Directory = System.getProperty("user.home") + "/.veridot/trust-cache";
    private Duration refreshThreshold = Duration.ofHours(1);
    private Duration staleWindow = Duration.ofMinutes(5);
    private Duration fullSyncInterval = Duration.ofHours(6);
    private Duration resolveWaitTimeout = Duration.ofSeconds(5);
    
    private String providerType = "tad";
    private List<String> tadClusterUrls;
    private Duration connectTimeout = Duration.ofSeconds(3);

    public int getL1MaxSize() { return l1MaxSize; }
    public void setL1MaxSize(int l1MaxSize) { this.l1MaxSize = l1MaxSize; }

    public String getL2Directory() { return l2Directory; }
    public void setL2Directory(String l2Directory) { this.l2Directory = l2Directory; }

    public Duration getRefreshThreshold() { return refreshThreshold; }
    public void setRefreshThreshold(Duration refreshThreshold) { this.refreshThreshold = refreshThreshold; }

    public Duration getStaleWindow() { return staleWindow; }
    public void setStaleWindow(Duration staleWindow) { this.staleWindow = staleWindow; }

    public Duration getFullSyncInterval() { return fullSyncInterval; }
    public void setFullSyncInterval(Duration fullSyncInterval) { this.fullSyncInterval = fullSyncInterval; }

    public Duration getResolveWaitTimeout() { return resolveWaitTimeout; }
    public void setResolveWaitTimeout(Duration resolveWaitTimeout) { this.resolveWaitTimeout = resolveWaitTimeout; }

    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }

    public List<String> getTadClusterUrls() { return tadClusterUrls; }
    public void setTadClusterUrls(List<String> tadClusterUrls) { this.tadClusterUrls = tadClusterUrls; }

    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
}
