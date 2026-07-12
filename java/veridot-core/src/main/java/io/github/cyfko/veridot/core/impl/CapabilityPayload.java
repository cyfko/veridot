package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.exceptions.VeridotException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Payload of a CAPABILITY entry (§4.3, §9).
 *
 * <p>Grants authorization for specific scopes/operations. V5 adds
 * {@code subjectPattern} (tag 0x05) as an alternative to {@code subjectSid} (tag 0x01).
 * Exactly one of these two fields MUST be present (XOR invariant, §4.3.1).
 */
record CapabilityPayload(
    String subjectSid,            // tag 0x01: exact subject identifier (mutually exclusive with subjectPattern)
    List<String> scopePatterns,   // tag 0x02: list of scope patterns
    byte maxDelegationDepth,      // tag 0x03: maximum delegation depth
    long validUntil,              // tag 0x04: epoch milliseconds
    String subjectPattern         // tag 0x05: subject pattern (mutually exclusive with subjectSid) — V5 NEW
) {

    public enum Tag {
        SUBJECT_SID((byte) 0x01),
        SCOPE_PATTERNS((byte) 0x02),
        MAX_DELEGATION_DEPTH((byte) 0x03),
        VALID_UNTIL((byte) 0x04),
        SUBJECT_PATTERN((byte) 0x05);

        public final byte code;
        Tag(byte code) { this.code = code; }
    }

    public static CapabilityPayload decode(byte[] tlvBytes) {
        Map<Byte, byte[]> fields = TlvCodec.parse(tlvBytes);

        boolean hasSubjectSid = fields.containsKey(Tag.SUBJECT_SID.code);
        boolean hasSubjectPattern = fields.containsKey(Tag.SUBJECT_PATTERN.code);

        // XOR invariant: exactly one of subjectSid (0x01) or subjectPattern (0x05) MUST be present
        if (hasSubjectSid == hasSubjectPattern) {
            throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null,
                "CAPABILITY payload must contain exactly one of tag 0x01 (subjectSid) or tag 0x05 (subjectPattern), "
                + "got: 0x01=" + hasSubjectSid + ", 0x05=" + hasSubjectPattern);
        }

        String subjectSid = hasSubjectSid
            ? TlvCodec.readString(fields, Tag.SUBJECT_SID.code, true) : null;
        List<String> scopePatterns = TlvCodec.readStringList(fields, Tag.SCOPE_PATTERNS.code, true);
        byte maxDelegationDepth = TlvCodec.readU8(fields, Tag.MAX_DELEGATION_DEPTH.code, true);
        long validUntil = TlvCodec.readI64(fields, Tag.VALID_UNTIL.code, true);
        String subjectPattern = hasSubjectPattern
            ? TlvCodec.readString(fields, Tag.SUBJECT_PATTERN.code, true) : null;

        return new CapabilityPayload(subjectSid, scopePatterns, maxDelegationDepth, validUntil, subjectPattern);
    }

    public byte[] encode() {
        List<TlvCodec.TlvField> fields = new ArrayList<>();

        // Write exactly one of subjectSid or subjectPattern
        if (subjectSid != null) {
            fields.add(TlvCodec.string(Tag.SUBJECT_SID.code, subjectSid));
        }
        fields.add(TlvCodec.stringList(Tag.SCOPE_PATTERNS.code, scopePatterns));
        fields.add(TlvCodec.u8(Tag.MAX_DELEGATION_DEPTH.code, maxDelegationDepth));
        fields.add(TlvCodec.i64(Tag.VALID_UNTIL.code, validUntil));
        if (subjectPattern != null) {
            fields.add(TlvCodec.string(Tag.SUBJECT_PATTERN.code, subjectPattern));
        }

        return TlvCodec.encode(fields);
    }

    /**
     * Checks if the scope matches at least one pattern in scopePatterns (§9.2.1).
     * Delegates to {@link PatternMatcher} for unified matching logic.
     */
    public boolean covers(Scope scope) {
        return PatternMatcher.matchesAny(scopePatterns, scope.value());
    }

    /**
     * Checks if the given subject matches this capability's subject targeting (§9.2.2).
     * If {@code subjectSid} is set, it must exactly match. If {@code subjectPattern} is set,
     * pattern matching is applied.
     *
     * @param subject the subject identifier to test
     * @return true if this capability applies to the given subject
     */
    public boolean matchesSubject(String subject) {
        if (subjectSid != null) {
            return subjectSid.equals(subject);
        }
        if (subjectPattern != null) {
            return PatternMatcher.matches(subjectPattern, subject);
        }
        return false;
    }
}
