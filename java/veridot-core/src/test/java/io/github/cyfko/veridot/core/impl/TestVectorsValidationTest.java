package io.github.cyfko.veridot.core.impl;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.cyfko.veridot.core.Algorithm;
import io.github.cyfko.veridot.core.exceptions.VeridotException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes all Protocol V5 test vectors from the JSON files and validates
 * compliance of the Java implementation.
 */
class TestVectorsValidationTest {

    // ── Subject Computer Test Vectors ────────────────────────────────────────

    @Test
    void validateSubjectComputerTestVectors() throws Exception {
        List<JsonNode> vectors = TestVectorLoader.load("subject_computer_vectors.json");

        for (JsonNode vector : vectors) {
            String name = vector.get("name").asText();
            JsonNode input = vector.get("input");
            JsonNode expected = vector.get("expected");

            try {
                if (expected.has("valid") && !expected.get("valid").asBoolean()) {
                    // Rejection vector
                    String cn = input.has("cn") && !input.get("cn").isNull() ? input.get("cn").asText() : null;
                    PublicKey pubKey = null;
                    if (input.has("publicKeyEncodedHex") && !input.get("publicKeyEncodedHex").isNull()) {
                        pubKey = parsePublicKey(input.get("publicKeyEncodedHex").asText(), input.get("algorithm").asText());
                    }

                    PublicKey finalPubKey = pubKey;
                    assertThrows(IllegalArgumentException.class, () -> SubjectComputer.compute(cn, finalPubKey),
                            "Expected rejection for: " + name);
                } else if (expected.has("isInstanceScoped")) {
                    boolean isScoped = expected.get("isInstanceScoped").asBoolean();

                    if (input.has("subject")) {
                        // isInstanceScoped format check
                        String subject = input.get("subject").asText();
                        assertEquals(isScoped, SubjectComputer.isInstanceScoped(subject), "Scoped status mismatch: " + name);
                    } else {
                        // subject computation check
                        String cn = input.get("cn").asText();
                        PublicKey pubKey;
                        if (input.has("publicKeyEncodedHex") && !input.get("publicKeyEncodedHex").isNull()) {
                            pubKey = parsePublicKey(input.get("publicKeyEncodedHex").asText(), input.get("algorithm").asText());
                        } else {
                            pubKey = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
                        }
                        String subject = SubjectComputer.compute(cn, pubKey);

                        assertEquals(isScoped, SubjectComputer.isInstanceScoped(subject), "Scoped status mismatch: " + name);
                        if (expected.has("extractedCn")) {
                            assertEquals(expected.get("extractedCn").asText(), SubjectComputer.extractCn(subject), "Extracted CN mismatch: " + name);
                        }
                        if (expected.has("hashLength")) {
                            assertEquals(expected.get("hashLength").asInt(), SubjectComputer.extractHash(subject).length(), "Hash length mismatch: " + name);
                        }
                    }
                } else if (expected.has("allResultsIdentical") && expected.get("allResultsIdentical").asBoolean()) {
                    // Determinism check
                    String cn = input.get("cn").asText();
                    java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("Ed25519");
                    PublicKey pubKey = generator.generateKeyPair().getPublic();

                    String first = SubjectComputer.compute(cn, pubKey);
                    for (int i = 0; i < input.get("iterations").asInt(100); i++) {
                        assertEquals(first, SubjectComputer.compute(cn, pubKey), "Determinism mismatch: " + name);
                    }
                } else if (expected.has("subjectsEqual") && !expected.get("subjectsEqual").asBoolean()) {
                    // Different CNs, same key check
                    java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("Ed25519");
                    PublicKey pubKey = generator.generateKeyPair().getPublic();
                    String cn1 = input.get("cn1").asText();
                    String cn2 = input.get("cn2").asText();

                    String s1 = SubjectComputer.compute(cn1, pubKey);
                    String s2 = SubjectComputer.compute(cn2, pubKey);

                    assertNotEquals(s1, s2, "Subjects must be different for different CNs: " + name);
                    assertEquals(SubjectComputer.extractHash(s1), SubjectComputer.extractHash(s2), "Hashes must match for same key: " + name);
                }
            } catch (Exception e) {
                if (expected.has("valid") && !expected.get("valid").asBoolean()) {
                    // Expected failure, but it might throw a different exception type
                    assertTrue(e instanceof IllegalArgumentException || e instanceof NullPointerException,
                            "Unexpected exception type: " + e.getClass().getName() + " for " + name);
                } else {
                    throw new AssertionError("Failed vector '" + name + "'", e);
                }
            }
        }
    }

    private PublicKey parsePublicKey(String hex, String alg) throws Exception {
        byte[] bytes = TestVectorLoader.hexToBytes(hex);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(bytes);
        String factoryAlg = alg;
        if ("ED25519".equals(alg)) {
            factoryAlg = "Ed25519";
        } else if ("ECDSA_P256".equals(alg) || "ECDSA".equals(alg)) {
            factoryAlg = "EC";
        }
        KeyFactory kf = KeyFactory.getInstance(factoryAlg);
        return kf.generatePublic(spec);
    }

    // ── Pattern Matcher Test Vectors ─────────────────────────────────────────

    @Test
    void validatePatternMatcherTestVectors() throws IOException {
        List<JsonNode> vectors = TestVectorLoader.load("pattern_matcher_vectors.json");

        for (JsonNode vector : vectors) {
            String name = vector.get("name").asText();
            JsonNode input = vector.get("input");
            JsonNode expected = vector.get("expected");

            try {
                if (expected.has("error")) {
                    String pattern = input.get("pattern").isNull() ? null : input.get("pattern").asText();
                    String value = input.get("value").isNull() ? null : input.get("value").asText();
                    assertThrows(IllegalArgumentException.class, () -> PatternMatcher.matches(pattern, value),
                            "Expected error for: " + name);
                } else if (expected.has("matches")) {
                    String pattern = input.get("pattern").asText();
                    String value = input.get("value").asText();
                    assertEquals(expected.get("matches").asBoolean(), PatternMatcher.matches(pattern, value),
                            "Match mismatch for: " + name);
                } else if (expected.has("matchesAny")) {
                    List<String> patterns = null;
                    if (!input.get("patterns").isNull()) {
                        patterns = new ArrayList<>();
                        for (JsonNode p : input.get("patterns")) {
                            patterns.add(p.asText());
                        }
                    }
                    String value = input.get("value").asText();
                    assertEquals(expected.get("matchesAny").asBoolean(), PatternMatcher.matchesAny(patterns, value),
                            "matchesAny mismatch for: " + name);
                }
            } catch (Exception e) {
                if (expected.has("error")) {
                    assertTrue(e instanceof IllegalArgumentException, "Expected IllegalArgumentException, got " + e.getClass().getName());
                } else {
                    throw new AssertionError("Failed vector '" + name + "'", e);
                }
            }
        }
    }

    // ── Envelope V5 Test Vectors ─────────────────────────────────────────────

    @Test
    void validateEnvelopeV5TestVectors() throws IOException {
        List<JsonNode> vectors = TestVectorLoader.load("envelope_v5_vectors.json");

        for (JsonNode vector : vectors) {
            String name = vector.get("name").asText();
            JsonNode input = vector.get("input");
            JsonNode expected = vector.get("expected");

            try {
                byte[] rawBytes = serializeEnvelope(input);

                if (expected.has("valid") && !expected.get("valid").asBoolean()) {
                    // Rejection vector
                    VeridotException ex = assertThrows(VeridotException.class, () -> Envelope.parse(rawBytes),
                            "Expected parsing rejection for: " + name);
                    if (expected.has("errorCode")) {
                        assertEquals(expected.get("errorCode").asText(), ex.getErrorCode().code,
                                "ErrorCode mismatch for: " + name);
                    }
                } else {
                    // Acceptance vector
                    Envelope parsed = Envelope.parse(rawBytes);

                    if (expected.has("entryType")) {
                        assertEquals(EntryType.valueOf(expected.get("entryType").asText()), parsed.entryType, "EntryType mismatch: " + name);
                    }
                    if (expected.has("scope")) {
                        assertEquals(expected.get("scope").asText(), parsed.scope.value(), "Scope mismatch: " + name);
                    }
                    if (expected.has("key")) {
                        assertEquals(expected.get("key").asText(), parsed.key, "Key mismatch: " + name);
                    }
                    if (expected.has("issuer")) {
                        assertEquals(expected.get("issuer").asText(), parsed.issuer, "Issuer mismatch: " + name);
                    }
                    if (expected.has("flagCompactSig")) {
                        assertEquals(expected.get("flagCompactSig").asBoolean(), Flags.has(parsed.flags, Flags.COMPACT_SIG), "COMPACT_SIG mismatch: " + name);
                    }
                    if (expected.has("flagHybridSig")) {
                        assertEquals(expected.get("flagHybridSig").asBoolean(), Flags.has(parsed.flags, Flags.HYBRID_SIG), "HYBRID_SIG mismatch: " + name);
                    }
                }
            } catch (Exception e) {
                if (expected.has("valid") && !expected.get("valid").asBoolean()) {
                    assertTrue(e instanceof VeridotException, "Expected VeridotException, got " + e.getClass().getName());
                } else {
                    throw new AssertionError("Failed vector '" + name + "'", e);
                }
            }
        }
    }

    private byte[] serializeEnvelope(JsonNode input) {
        if (input.has("magicAndHeaderHex")) {
            return TestVectorLoader.hexToBytes(input.get("magicAndHeaderHex").asText());
        }
        if (input.has("description") && input.get("description").asText().contains("extra byte")) {
            EnvelopeBuilder builder = new EnvelopeBuilder()
                    .entryType(EntryType.LIVENESS)
                    .flags(0)
                    .scope(Scope.global())
                    .key("k")
                    .version(1L)
                    .timestamp(1L)
                    .issuer("i")
                    .payload(new byte[0])
                    .sigAlg(Algorithm.RSA_SHA256);
            byte[] validEnv = Envelope.encode(builder, new byte[256]);
            byte[] badEnv = new byte[validEnv.length + 1];
            System.arraycopy(validEnv, 0, badEnv, 0, validEnv.length);
            badEnv[validEnv.length] = (byte) 0xFF;
            return badEnv;
        }

        byte[] magic = input.has("magicHex") ? TestVectorLoader.hexToBytes(input.get("magicHex").asText()) : new byte[]{0x56, 0x44};
        byte protoVersion = input.has("protoVersion") ? (byte) input.get("protoVersion").asInt(5) : 5;
        byte entryTypeCode = input.has("entryTypeCode") ? parseHexOrInt(input.get("entryTypeCode")) : 0x04;
        int flags = input.has("flags") ? input.get("flags").asInt() : (input.has("flagsHex") ? Integer.parseInt(input.get("flagsHex").asText().substring(2), 16) : 1);

        byte[] scopeBytes = input.has("scope") && !input.get("scope").isNull() ? input.get("scope").asText().getBytes(StandardCharsets.UTF_8) : "global".getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = input.has("key") && !input.get("key").isNull() ? input.get("key").asText().getBytes(StandardCharsets.UTF_8) : "k".getBytes(StandardCharsets.UTF_8);
        long version = input.has("version") && !input.get("version").isNull() ? input.get("version").asLong(0L) : 0L;
        long timestamp = input.has("timestamp") && !input.get("timestamp").isNull() ? input.get("timestamp").asLong(0L) : 0L;
        byte[] issuerBytes = input.has("issuer") && !input.get("issuer").isNull() ? input.get("issuer").asText().getBytes(StandardCharsets.UTF_8) : "dummy-issuer".getBytes(StandardCharsets.UTF_8);

        byte[] payloadBytes = input.has("payloadHex") && !input.get("payloadHex").isNull() ? TestVectorLoader.hexToBytes(input.get("payloadHex").asText()) : new byte[0];
        byte sigAlgCode = input.has("algorithmCode") ? parseHexOrInt(input.get("algorithmCode")) : 0x01;
        byte[] signatureBytes = input.has("signatureHex") && !input.get("signatureHex").isNull() ? TestVectorLoader.hexToBytes(input.get("signatureHex").asText()) : new byte[64];

        int totalLen = magic.length + 1 + 1 + 2 + 2 + scopeBytes.length + 2 + keyBytes.length
                + 8 + 8 + 2 + issuerBytes.length + 4 + payloadBytes.length + 1 + 2 + signatureBytes.length;
        ByteBuffer buffer = ByteBuffer.allocate(totalLen);

        buffer.put(magic);                                         // Magic
        buffer.put(protoVersion);                                  // ProtoVersion
        buffer.put(entryTypeCode);                                 // EntryType
        buffer.putShort((short) flags);                            // Flags
        buffer.putShort((short) scopeBytes.length);                // ScopeLen
        buffer.put(scopeBytes);
        buffer.putShort((short) keyBytes.length);                  // KeyLen
        buffer.put(keyBytes);
        buffer.putLong(version);                                   // Version
        buffer.putLong(timestamp);                                 // Timestamp
        buffer.putShort((short) issuerBytes.length);               // IssuerLen
        buffer.put(issuerBytes);
        buffer.putInt(payloadBytes.length);                        // PayloadLen
        buffer.put(payloadBytes);
        buffer.put(sigAlgCode);                                    // SigAlg
        buffer.putShort((short) signatureBytes.length);            // SigLen
        buffer.put(signatureBytes);

        return buffer.array();
    }

    private byte parseHexOrInt(JsonNode node) {
        if (node == null || node.isNull()) return 0;
        String text = node.asText();
        if (text.startsWith("0x") || text.startsWith("0X")) {
            return (byte) Integer.parseInt(text.substring(2), 16);
        }
        return (byte) node.asInt();
    }
}
