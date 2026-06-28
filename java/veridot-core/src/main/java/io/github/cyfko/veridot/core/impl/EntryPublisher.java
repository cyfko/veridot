package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Broker;
import io.github.cyfko.veridot.core.exceptions.VeridotException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.concurrent.CompletableFuture;

/**
 * Signs and publishes entries to the broker (§3.4, §12.3.1).
 */
final class EntryPublisher {

    /**
     * Encodes, cryptographically signs, caches locally, and publishes an entry to the broker.
     */
    public CompletableFuture<Void> publish(EntryType type, Scope scope, String key,
                                           long version, byte[] payload,
                                           PrivateKey signingKey, byte sigAlg,
                                           String issuer, Broker broker) {
        if (type == null) {
            throw new IllegalArgumentException("EntryType cannot be null");
        }
        if (scope == null) {
            throw new IllegalArgumentException("Scope cannot be null");
        }
        if (signingKey == null) {
            throw new IllegalArgumentException("Signing key cannot be null");
        }
        if (issuer == null || issuer.isEmpty()) {
            throw new IllegalArgumentException("Issuer cannot be null or empty");
        }
        if (broker == null) {
            throw new IllegalArgumentException("Broker cannot be null");
        }

        EntryId entryId = new EntryId(scope, type, key);
        String loggable = entryId.loggable();

        byte flags = (byte) (sigAlg == 0x02 ? 0x01 : 0x00);

        EnvelopeBuilder builder = new EnvelopeBuilder()
                .entryType(type)
                .flags(flags)
                .scope(scope)
                .key(key)
                .version(version)
                .timestamp(System.currentTimeMillis())
                .issuer(issuer)
                .payload(payload)
                .sigAlg(sigAlg);

        // Pre-build envelope to compute canonical signing bytes
        Envelope tempEnvelope = new Envelope(
                Envelope.PROTO_VERSION, type, flags, scope, key,
                version, builder.timestamp, issuer, payload, sigAlg, null
        );

        byte[] signatureBytes;
        try {
            Signature sig;
            if (sigAlg == 0x01) {
                sig = Signature.getInstance("SHA256withRSA");
            } else if (sigAlg == 0x02) {
                sig = Signature.getInstance("Ed25519");
            } else if (sigAlg == 0x03) {
                sig = Signature.getInstance("SHA256withRSA/PSS");
            } else {
                throw new VeridotException(ErrorCode.SIGALG_KEY_MISMATCH, loggable, "Unsupported signature algorithm: " + sigAlg);
            }
            sig.initSign(signingKey);
            sig.update(tempEnvelope.canonicalSigningBytes());
            signatureBytes = sig.sign();
        } catch (VeridotException e) {
            throw e;
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, loggable, "Failed to generate cryptographic signature", e);
        }

        byte[] envelopeBytes = Envelope.encode(builder, signatureBytes);
        byte[] storageKey = entryId.storageKey();

        // Write to local cache first to prevent read-after-write race
        try {
            broker.putLocal(storageKey, envelopeBytes);
        } catch (Exception e) {
            // Log or ignore local cache failure, but don't fail publishing
        }

        // Put to broker asynchronously
        return broker.put(storageKey, envelopeBytes);
    }
}
