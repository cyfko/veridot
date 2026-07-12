package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.exceptions.VeridotException;

/**
 * Utility class for Veridot Protocol V5 envelope flags.
 *
 * <p>In V5 the flags field is widened from {@code u8} (V4) to {@code u16},
 * encoded as two bytes in big-endian (network) order.
 * Only bits 0–3 are currently defined; bits 4–15 are reserved and
 * <strong>MUST</strong> be zero.  Setting any reserved bit is a protocol
 * violation ({@code V5005 RESERVED_FLAG_SET}).
 *
 * <h2>Bit Registry (Protocol V5 §3.3, Appendix C.4)</h2>
 * <table>
 *   <caption>V5 flag bits</caption>
 *   <tr><th>Bit</th><th>Name</th><th>Description</th></tr>
 *   <tr><td>0</td><td>{@link #COMPACT_SIG}</td>
 *       <td>Signature uses compact (fixed-length) encoding (§3.3)</td></tr>
 *   <tr><td>1</td><td>{@link #HYBRID_SIG}</td>
 *       <td>Envelope carries both classical and PQ signatures (§6.2)</td></tr>
 *   <tr><td>2</td><td>{@link #DETACHED_PAYLOAD}</td>
 *       <td>Payload is not embedded in the envelope (§3.3)</td></tr>
 *   <tr><td>3</td><td>{@link #INSTANCE_SCOPED}</td>
 *       <td>Subject is instance-scoped — single key per instance (§5.1)</td></tr>
 *   <tr><td>4–15</td><td><em>reserved</em></td>
 *       <td>MUST be zero; non-zero → {@code V5005 RESERVED_FLAG_SET}</td></tr>
 * </table>
 *
 * @see ErrorCode#RESERVED_FLAG_SET
 */
public final class Flags {

    // ──────────────────────────────────────────────────────────
    //  Defined flag constants (bits 0–3)
    // ──────────────────────────────────────────────────────────

    /**
     * Bit 0 – Signature uses compact (fixed-length) encoding.
     * <p>See Protocol V5 §3.3.
     */
    public static final int COMPACT_SIG      = 1 << 0;  // 0x0001

    /**
     * Bit 1 – Envelope carries both a classical and a post-quantum signature.
     * <p>See Protocol V5 §6.2.
     */
    public static final int HYBRID_SIG       = 1 << 1;  // 0x0002

    /**
     * Bit 2 – Payload is detached (not embedded in the envelope).
     * <p>See Protocol V5 §3.3.
     */
    public static final int DETACHED_PAYLOAD = 1 << 2;  // 0x0004

    /**
     * Bit 3 – Subject is instance-scoped (single key per instance).
     * <p>See Protocol V5 §5.1.
     */
    public static final int INSTANCE_SCOPED  = 1 << 3;  // 0x0008

    // ──────────────────────────────────────────────────────────
    //  Masks
    // ──────────────────────────────────────────────────────────

    /**
     * Mask covering all currently defined flag bits (bits 0–3).
     */
    private static final int DEFINED_MASK  = COMPACT_SIG | HYBRID_SIG | DETACHED_PAYLOAD | INSTANCE_SCOPED;

    /**
     * Mask covering the reserved range (bits 4–15).
     * Any bit set in this range is a protocol violation.
     */
    private static final int RESERVED_MASK = 0xFFF0;

    // ──────────────────────────────────────────────────────────
    //  Non-instantiable
    // ──────────────────────────────────────────────────────────

    /** Prevent instantiation. */
    private Flags() {}

    // ──────────────────────────────────────────────────────────
    //  Bitwise helpers
    // ──────────────────────────────────────────────────────────

    /**
     * Tests whether a specific flag bit is set.
     *
     * @param flags the current flags value (u16)
     * @param flag  the flag constant to test (e.g. {@link #COMPACT_SIG})
     * @return {@code true} if {@code flag} is set in {@code flags}
     */
    public static boolean has(int flags, int flag) {
        return (flags & flag) != 0;
    }

    /**
     * Returns a new flags value with the given flag bit set.
     *
     * @param flags the current flags value (u16)
     * @param flag  the flag constant to set (e.g. {@link #HYBRID_SIG})
     * @return the updated flags value
     */
    public static int set(int flags, int flag) {
        return flags | flag;
    }

    /**
     * Returns a new flags value with the given flag bit cleared.
     *
     * @param flags the current flags value (u16)
     * @param flag  the flag constant to clear (e.g. {@link #DETACHED_PAYLOAD})
     * @return the updated flags value
     */
    public static int clear(int flags, int flag) {
        return flags & ~flag;
    }

    // ──────────────────────────────────────────────────────────
    //  Validation
    // ──────────────────────────────────────────────────────────

    /**
     * Validates that no reserved bits (4–15) are set.
     *
     * <p>If any reserved bit is non-zero, a {@link VeridotException} is thrown
     * with error code {@link ErrorCode#RESERVED_FLAG_SET} ({@code V5005}).
     *
     * @param flags the flags value to validate
     * @throws VeridotException if any reserved bit is set
     * @see ErrorCode#RESERVED_FLAG_SET
     */
    public static void validateReservedBits(int flags) {
        if ((flags & RESERVED_MASK) != 0) {
            throw new VeridotException(
                    ErrorCode.RESERVED_FLAG_SET,
                    null,
                    "Reserved flag bits are set: 0x"
                            + String.format("%04X", flags & RESERVED_MASK)
                            + " (raw flags=0x"
                            + String.format("%04X", flags & 0xFFFF)
                            + ")"
            );
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Wire encoding / decoding  (u16 big-endian)
    // ──────────────────────────────────────────────────────────

    /**
     * Encodes the flags value as 2 bytes in big-endian (network) order.
     *
     * <p>Only the lower 16 bits of {@code flags} are encoded.
     *
     * @param flags the flags value to encode
     * @return a 2-element byte array in big-endian order
     */
    public static byte[] encode(int flags) {
        return new byte[]{
                (byte) ((flags >>> 8) & 0xFF),
                (byte) (flags & 0xFF)
        };
    }

    /**
     * Decodes 2 bytes at the given offset from {@code data} as a big-endian
     * unsigned 16-bit flags value.
     *
     * @param data   the byte array containing the encoded flags
     * @param offset the offset into {@code data} where the two flag bytes begin
     * @return the decoded flags value as an {@code int} in the range 0–65535
     * @throws ArrayIndexOutOfBoundsException if {@code offset + 1} exceeds
     *                                        the array length
     */
    public static int decode(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }
}
