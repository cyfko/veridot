package io.github.cyfko.veridot.core.impl;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 256-level Sparse Merkle Tree for State Transparency (§18.2).
 *
 * <p>Computes a deterministic root hash from a set of (key, value) leaf pairs.
 * The tree has 256 levels (one per bit of a SHA-256 key). Empty leaves use
 * {@code SHA-256(0x00)} as the default hash.
 *
 * <p>Determinism guarantee: the same set of leaves always produces the same root
 * regardless of insertion order. This is achieved by sorting leaves by key before
 * building the tree.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Sort all leaves lexicographically by 32-byte key</li>
 *   <li>Recursively partition leaves by the bit at the current depth</li>
 *   <li>If a partition is empty, return the precomputed empty subtree hash for that depth</li>
 *   <li>If a partition has exactly one leaf at a leaf level, return its value hash</li>
 *   <li>Internal node hash = SHA-256(leftChild || rightChild)</li>
 * </ol>
 */
public final class SparseMerkleTree {

    /** Tree depth: one level per bit of a 256-bit (32-byte) key. */
    private static final int TREE_DEPTH = 256;

    /** Precomputed default (empty) hash for leaf level: SHA-256(0x00). */
    private static final byte[] EMPTY_LEAF_HASH;

    /**
     * Precomputed empty subtree hashes for each depth.
     * {@code EMPTY_HASHES[depth]} is the hash of an entirely empty subtree rooted at {@code depth}.
     * {@code EMPTY_HASHES[TREE_DEPTH]} = EMPTY_LEAF_HASH (leaf level).
     * {@code EMPTY_HASHES[d]} = SHA-256(EMPTY_HASHES[d+1] || EMPTY_HASHES[d+1]).
     */
    private static final byte[][] EMPTY_HASHES;

    static {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            EMPTY_LEAF_HASH = md.digest(new byte[]{0x00});

            EMPTY_HASHES = new byte[TREE_DEPTH + 1][];
            EMPTY_HASHES[TREE_DEPTH] = EMPTY_LEAF_HASH;
            for (int d = TREE_DEPTH - 1; d >= 0; d--) {
                md.reset();
                md.update(EMPTY_HASHES[d + 1]);
                md.update(EMPTY_HASHES[d + 1]);
                EMPTY_HASHES[d] = md.digest();
            }
        } catch (NoSuchAlgorithmException e) {
            throw new ExceptionInInitializerError("SHA-256 not available");
        }
    }

    private SparseMerkleTree() {} // utility class

    /**
     * Computes the 32-byte Sparse Merkle Tree root from a list of (key, value) leaf pairs.
     *
     * <p>Both key and value MUST be 32-byte SHA-256 hashes. The result is deterministic:
     * the same set of leaves always produces the same root regardless of the input order.
     *
     * @param leaves list of (key, value) pairs where key and value are each 32 bytes
     * @return 32-byte SMT root hash
     * @throws IllegalArgumentException if any key or value is not exactly 32 bytes
     */
    public static byte[] computeRoot(List<Map.Entry<byte[], byte[]>> leaves) {
        if (leaves == null || leaves.isEmpty()) {
            return EMPTY_HASHES[0];
        }

        // Validate and sort leaves by key for determinism
        byte[][] sortedKeys = new byte[leaves.size()][];
        byte[][] sortedValues = new byte[leaves.size()][];

        // Create index array for sorting
        Integer[] indices = new Integer[leaves.size()];
        for (int i = 0; i < leaves.size(); i++) {
            indices[i] = i;
            Map.Entry<byte[], byte[]> leaf = leaves.get(i);
            if (leaf.getKey().length != 32) {
                throw new IllegalArgumentException(
                    "Leaf key must be 32 bytes, got " + leaf.getKey().length);
            }
            if (leaf.getValue().length != 32) {
                throw new IllegalArgumentException(
                    "Leaf value must be 32 bytes, got " + leaf.getValue().length);
            }
        }

        // Sort indices by key (lexicographic comparison of byte arrays)
        Arrays.sort(indices, Comparator.comparing(
            i -> leaves.get(i).getKey(), SparseMerkleTree::compareBytes));

        for (int i = 0; i < indices.length; i++) {
            Map.Entry<byte[], byte[]> leaf = leaves.get(indices[i]);
            sortedKeys[i] = leaf.getKey();
            sortedValues[i] = leaf.getValue();
        }

        return buildSubtree(sortedKeys, sortedValues, 0, sortedKeys.length, 0);
    }

    /**
     * Recursively builds a subtree from sorted leaves partitioned by bit at the current depth.
     *
     * @param keys   sorted leaf keys (32 bytes each)
     * @param values leaf values (32 bytes each), parallel to keys
     * @param from   start index (inclusive) in the keys/values arrays
     * @param to     end index (exclusive) in the keys/values arrays
     * @param depth  current tree depth (0 = root, 255 = leaf level)
     * @return 32-byte hash of this subtree
     */
    private static byte[] buildSubtree(byte[][] keys, byte[][] values,
                                        int from, int to, int depth) {
        // Empty partition → return precomputed empty hash for this depth
        if (from >= to) {
            return EMPTY_HASHES[depth];
        }

        // Leaf level → return the value of the single leaf (or hash if multiple collisions)
        if (depth == TREE_DEPTH) {
            // At leaf level, return the value of the last inserted leaf for this key
            return values[to - 1];
        }

        // Partition leaves by the bit at the current depth
        // bit = 0 → left subtree, bit = 1 → right subtree
        int mid = partitionByBit(keys, from, to, depth);

        byte[] leftHash = buildSubtree(keys, values, from, mid, depth + 1);
        byte[] rightHash = buildSubtree(keys, values, mid, to, depth + 1);

        // Internal node hash = SHA-256(left || right)
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(leftHash);
            md.update(rightHash);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Partitions the range [from, to) by the bit at the specified depth.
     * Since keys are sorted, all keys with bit=0 at this depth come before bit=1.
     * Returns the index where bit transitions from 0 to 1.
     *
     * @param keys  sorted leaf keys
     * @param from  start index (inclusive)
     * @param to    end index (exclusive)
     * @param depth bit position (0 = MSB of byte 0, 7 = LSB of byte 0, 8 = MSB of byte 1, etc.)
     * @return partition index: [from, result) has bit=0, [result, to) has bit=1
     */
    private static int partitionByBit(byte[][] keys, int from, int to, int depth) {
        int byteIndex = depth / 8;
        int bitIndex = 7 - (depth % 8); // MSB first

        // Binary search for the transition point where bit changes from 0 to 1
        int lo = from, hi = to;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (getBit(keys[mid], byteIndex, bitIndex) == 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Gets the bit at the specified position in a byte array.
     *
     * @param key       the byte array
     * @param byteIndex index of the byte
     * @param bitIndex  index of the bit within the byte (7 = MSB, 0 = LSB)
     * @return 0 or 1
     */
    private static int getBit(byte[] key, int byteIndex, int bitIndex) {
        return (key[byteIndex] >>> bitIndex) & 1;
    }

    /**
     * Lexicographic comparison of two byte arrays (unsigned).
     */
    private static int compareBytes(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int diff = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (diff != 0) return diff;
        }
        return a.length - b.length;
    }
}
