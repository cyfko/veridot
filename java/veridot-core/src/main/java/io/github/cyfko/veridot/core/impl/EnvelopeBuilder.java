package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Algorithm;

/**
 * Fluent builder for creating Envelope components before signature application.
 */
final class EnvelopeBuilder {
    public EntryType entryType;
    public byte flags;
    public Scope scope;
    public String key = "";
    public long version;
    public long timestamp;
    public String issuer;
    public byte[] payload;
    public Algorithm sigAlg;

    public EnvelopeBuilder() {}

    public EnvelopeBuilder entryType(EntryType entryType) {
        this.entryType = entryType;
        return this;
    }

    public EnvelopeBuilder flags(byte flags) {
        this.flags = flags;
        return this;
    }

    public EnvelopeBuilder scope(Scope scope) {
        this.scope = scope;
        return this;
    }

    public EnvelopeBuilder key(String key) {
        this.key = key;
        return this;
    }

    public EnvelopeBuilder version(long version) {
        this.version = version;
        return this;
    }

    public EnvelopeBuilder timestamp(long timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public EnvelopeBuilder issuer(String issuer) {
        this.issuer = issuer;
        return this;
    }

    public EnvelopeBuilder payload(byte[] payload) {
        this.payload = payload;
        return this;
    }

    public EnvelopeBuilder sigAlg(Algorithm sigAlg) {
        this.sigAlg = sigAlg;
        return this;
    }

    @Deprecated
    public EnvelopeBuilder sigAlg(byte sigAlgCode) {
        this.sigAlg = Algorithm.fromCode(sigAlgCode);
        return this;
    }
}
