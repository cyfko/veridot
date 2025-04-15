package io.github.cyfko.veridot.core;

/**
 * Enumeration representing the token handling mode used during signing and verification processes.
 *
 * <p>Supported modes:</p>
 * <ul>
 *   <li><strong>jwt</strong>: The token itself contains the signed data, encoded as a JWT (JSON Web Token).</li>
 *   <li><strong>id</strong>: Only a unique identifier is returned as the token; the actual signed JWT is
 *   stored in an external broker and later fetched using the ID as the key.</li>
 * </ul>
 *
 * <p>This enum is primarily used to determine how a signed token is returned and interpreted by the consumer and
 * the verifier logic.</p>
 */
public enum TokenMode {
    jwt,
    id,
}

