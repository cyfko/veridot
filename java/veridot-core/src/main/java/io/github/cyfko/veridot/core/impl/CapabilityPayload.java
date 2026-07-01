package io.github.cyfko.veridot.core.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Payload of a CAPABILITY entry (§6.2).
 * Grants authorization for specific scopes/operations.
 */
record CapabilityPayload(
    String subjectSid,            // tag 0x01
    List<String> scopePatterns,   // tag 0x02
    byte maxDelegationDepth,      // tag 0x03
    long validUntil               // tag 0x04
) {

    public enum Tag {
        SUBJECT_SID((byte) 0x01),
        SCOPE_PATTERNS((byte) 0x02),
        MAX_DELEGATION_DEPTH((byte) 0x03),
        VALID_UNTIL((byte) 0x04);

        public final byte code;
        Tag(byte code) { this.code = code; }
    }

    public static CapabilityPayload decode(byte[] tlvBytes) {
        Map<Byte, byte[]> fields = TlvCodec.parse(tlvBytes);

        String subjectSid = TlvCodec.readString(fields, Tag.SUBJECT_SID.code, true);
        List<String> scopePatterns = TlvCodec.readStringList(fields, Tag.SCOPE_PATTERNS.code, true);
        byte maxDelegationDepth = TlvCodec.readU8(fields, Tag.MAX_DELEGATION_DEPTH.code, true);
        long validUntil = TlvCodec.readI64(fields, Tag.VALID_UNTIL.code, true);

        return new CapabilityPayload(subjectSid, scopePatterns, maxDelegationDepth, validUntil);
    }

    public byte[] encode() {
        List<TlvCodec.TlvField> fields = new ArrayList<>();
        fields.add(TlvCodec.string(Tag.SUBJECT_SID.code, subjectSid));
        fields.add(TlvCodec.stringList(Tag.SCOPE_PATTERNS.code, scopePatterns));
        fields.add(TlvCodec.u8(Tag.MAX_DELEGATION_DEPTH.code, maxDelegationDepth));
        fields.add(TlvCodec.i64(Tag.VALID_UNTIL.code, validUntil));
        return TlvCodec.encode(fields);
    }

    /**
     * Checks if the scope matches at least one pattern in scopePatterns (§6.3).
     */
    public boolean covers(Scope scope) {
        if (scopePatterns == null || scopePatterns.isEmpty()) {
            return false;
        }

        String scopeVal = scope.value();
        for (String pattern : scopePatterns) {
            if ("*".equals(pattern)) {
                return true;
            }
            if (pattern.endsWith(":*")) {
                String prefix = pattern.substring(0, pattern.length() - 2);
                if (scopeVal.equals(prefix) || scopeVal.startsWith(prefix + ":")) {
                    return true;
                }
            } else if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (scopeVal.startsWith(prefix)) {
                    return true;
                }
            } else {
                if (scopeVal.equals(pattern)) {
                    return true;
                }
            }
        }
        return false;
    }
}
