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

    public static CapabilityPayload decode(byte[] tlvBytes) {
        Map<Byte, byte[]> fields = TlvCodec.parse(tlvBytes);

        String subjectSid = TlvCodec.readString(fields, (byte) 0x01, true);
        List<String> scopePatterns = TlvCodec.readStringList(fields, (byte) 0x02, true);
        byte maxDelegationDepth = TlvCodec.readU8(fields, (byte) 0x03, true);
        long validUntil = TlvCodec.readI64(fields, (byte) 0x04, true);

        return new CapabilityPayload(subjectSid, scopePatterns, maxDelegationDepth, validUntil);
    }

    public byte[] encode() {
        List<TlvCodec.TlvField> fields = new ArrayList<>();
        fields.add(TlvCodec.string((byte) 0x01, subjectSid));
        fields.add(TlvCodec.stringList((byte) 0x02, scopePatterns));
        fields.add(TlvCodec.u8((byte) 0x03, maxDelegationDepth));
        fields.add(TlvCodec.i64((byte) 0x04, validUntil));
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
