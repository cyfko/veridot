package io.github.cyfko.veridot.core.impl;

import org.junit.jupiter.api.Test;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SubjectComputer} — V5 §5.1 deterministic subject identifier computation.
 */
class SubjectComputerTest {

    // ── compute() ────────────────────────────────────────────────────

    @Test
    void compute_deterministic_sameKeyProducesSameSubject() throws Exception {
        KeyPair kp = ed25519KeyPair();
        String s1 = SubjectComputer.compute("orders-svc", kp.getPublic());
        String s2 = SubjectComputer.compute("orders-svc", kp.getPublic());
        assertEquals(s1, s2, "Same CN + same key must always yield the same subject");
    }

    @Test
    void compute_differentKeysProduceDifferentSubjects() throws Exception {
        KeyPair kp1 = ed25519KeyPair();
        KeyPair kp2 = ed25519KeyPair();
        String s1 = SubjectComputer.compute("svc", kp1.getPublic());
        String s2 = SubjectComputer.compute("svc", kp2.getPublic());
        assertNotEquals(s1, s2, "Different keys must produce different subjects");
    }

    @Test
    void compute_differentCnsSameKeyProduceDifferentSubjects() throws Exception {
        KeyPair kp = ed25519KeyPair();
        String s1 = SubjectComputer.compute("alpha", kp.getPublic());
        String s2 = SubjectComputer.compute("beta", kp.getPublic());
        assertNotEquals(s1, s2, "Different CNs must produce different subjects (CN is prefix)");
        // But the hash part should be the same since same key
        assertEquals(SubjectComputer.extractHash(s1), SubjectComputer.extractHash(s2),
                "Same key should yield same hash regardless of CN");
    }

    @Test
    void compute_formatIsCnAtHash() throws Exception {
        KeyPair kp = ed25519KeyPair();
        String subject = SubjectComputer.compute("my-service", kp.getPublic());
        assertTrue(subject.contains("@"), "Subject must contain '@'");
        assertTrue(subject.startsWith("my-service@"), "Subject must start with 'CN@'");
    }

    @Test
    void compute_hashPartIs32Chars() throws Exception {
        KeyPair kp = ed25519KeyPair();
        String subject = SubjectComputer.compute("svc", kp.getPublic());
        String hash = SubjectComputer.extractHash(subject);
        assertEquals(32, hash.length(), "Hash portion must be exactly 32 base64url chars (192 bits)");
    }

    @Test
    void compute_nullCnThrows() throws Exception {
        KeyPair kp = ed25519KeyPair();
        assertThrows(IllegalArgumentException.class,
                () -> SubjectComputer.compute(null, kp.getPublic()));
    }

    @Test
    void compute_blankCnThrows() throws Exception {
        KeyPair kp = ed25519KeyPair();
        assertThrows(IllegalArgumentException.class,
                () -> SubjectComputer.compute("   ", kp.getPublic()));
    }

    @Test
    void compute_cnWithAtSignThrows() throws Exception {
        KeyPair kp = ed25519KeyPair();
        assertThrows(IllegalArgumentException.class,
                () -> SubjectComputer.compute("bad@cn", kp.getPublic()));
    }

    @Test
    void compute_nullPublicKeyThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> SubjectComputer.compute("svc", null));
    }

    // ── extractCn() ──────────────────────────────────────────────────

    @Test
    void extractCn_returnsCorrectCnPart() throws Exception {
        KeyPair kp = ed25519KeyPair();
        String subject = SubjectComputer.compute("orders", kp.getPublic());
        assertEquals("orders", SubjectComputer.extractCn(subject));
    }

    @Test
    void extractCn_nullSubjectThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> SubjectComputer.extractCn(null));
    }

    @Test
    void extractCn_noAtSignThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> SubjectComputer.extractCn("no-at-sign"));
    }

    // ── extractHash() ────────────────────────────────────────────────

    @Test
    void extractHash_returnsCorrectHashPart() throws Exception {
        KeyPair kp = ed25519KeyPair();
        String subject = SubjectComputer.compute("payments", kp.getPublic());
        String hash = SubjectComputer.extractHash(subject);
        assertNotNull(hash);
        assertFalse(hash.isEmpty());
        assertEquals(32, hash.length());
    }

    @Test
    void extractHash_nullSubjectThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> SubjectComputer.extractHash(null));
    }

    // ── isInstanceScoped() ───────────────────────────────────────────

    @Test
    void isInstanceScoped_trueForComputedSubject() throws Exception {
        KeyPair kp = ed25519KeyPair();
        String subject = SubjectComputer.compute("svc", kp.getPublic());
        assertTrue(SubjectComputer.isInstanceScoped(subject));
    }

    @Test
    void isInstanceScoped_falseForLegacySubject() {
        assertFalse(SubjectComputer.isInstanceScoped("legacy-signer-id"));
    }

    @Test
    void isInstanceScoped_falseForNull() {
        assertFalse(SubjectComputer.isInstanceScoped(null));
    }

    @Test
    void isInstanceScoped_falseForEmptyString() {
        assertFalse(SubjectComputer.isInstanceScoped(""));
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static KeyPair ed25519KeyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }
}
