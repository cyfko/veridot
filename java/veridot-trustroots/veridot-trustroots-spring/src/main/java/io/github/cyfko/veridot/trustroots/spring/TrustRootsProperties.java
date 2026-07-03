package io.github.cyfko.veridot.trustroots.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Propriétés de configuration Spring Boot préfixées par {@code veridot.trustroots}.
 * Permet de paramétrer les différents aspects de la gestion du cache de clés de confiance et du client TAD.
 */
@ConfigurationProperties(prefix = "veridot.trustroots")
public class TrustRootsProperties {

    /** Nombre maximal de clés stockées simultanément en mémoire dans le cache L1. Valeur par défaut : 10000. */
    private int l1MaxSize = 10000;
    
    /** Répertoire physique utilisé par RocksDB pour le cache local L2. Valeur par défaut : {@code ~/.veridot/trust-cache}. */
    private String l2Directory = System.getProperty("user.home") + "/.veridot/trust-cache";
    
    /** Seuil avant expiration nominale pour déclencher un rafraîchissement asynchrone (L1/L2). Valeur par défaut : 1 heure. */
    private Duration refreshThreshold = Duration.ofHours(1);
    
    /** Fenêtre temporelle de validité transitoire de secours (Stale Window). Valeur par défaut : 5 minutes. */
    private Duration staleWindow = Duration.ofMinutes(5);
    
    /** Intervalle de temps pour exécuter la tâche récurrente de synchronisation incrémentale d'arrière-plan. Valeur par défaut : 6 heures. */
    private Duration fullSyncInterval = Duration.ofHours(6);
    
    /** Délai maximum toléré lors d'une tentative de résolution concurrente au démarrage du cache. Valeur par défaut : 5 secondes. */
    private Duration resolveWaitTimeout = Duration.ofSeconds(5);
    
    /** Type de fournisseur de registre utilisé. Actuellement seule l'autorité répliquée {@code "tad"} est supportée. */
    private String providerType = "tad";
    
    /** Liste des adresses de base des nœuds du cluster TAD (ex: http://127.0.0.1:8443). */
    private List<String> tadClusterUrls;
    
    /** Timeout de connexion et de lecture réseau pour interroger le TAD. Valeur par défaut : 3 secondes. */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /**
     * Constructeur par défaut.
     */
    public TrustRootsProperties() {
    }

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
