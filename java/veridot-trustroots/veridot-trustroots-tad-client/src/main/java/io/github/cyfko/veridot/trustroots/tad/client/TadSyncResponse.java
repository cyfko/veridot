package io.github.cyfko.veridot.trustroots.tad.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;

import java.util.List;

/**
 * Représente la réponse JSON de l'API TAD lors d'une synchronisation incrémentale.
 *
 * @param entries La liste des entrées de confiance modifiées ou ajoutées.
 * @param nextSyncToken Jeton temporel ou d'indexation pour la prochaine synchronisation.
 * @param truncated Indique si les résultats ont été tronqués (pagination requise).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record TadSyncResponse(
    List<TrustEntry> entries,
    String nextSyncToken,
    boolean truncated
) {}
