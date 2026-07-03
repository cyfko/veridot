package io.github.cyfko.veridot.trustroots.tad.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record TadSyncResponse(
    List<TrustEntry> entries,
    String nextSyncToken,
    boolean truncated
) {}
