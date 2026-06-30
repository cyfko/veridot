# TokenVerifier & VerifiedData

The `TokenVerifier` interface is the core entry point for verifier nodes. It validates the cryptographic signature of incoming tokens, checks the capability chains and version watermarks, and asserts session liveness.

---

## 1. Interface: TokenVerifier

### Method
```java
<T> VerifiedData<T> verify(String token, Function<String, T> deserializer)
    throws BrokerExtractionException, DataDeserializationException;
```

- **Role**: Validates a token and extracts its deserialized payload.
- **Parameters**:
  - `token`: The token to verify (signed JWT or `messageId` string; must not be `null` or empty).
  - `deserializer`: Function mapping the raw payload string to target type `T` (must not be `null`).
- **Returns**:
  - A `VerifiedData<T>` instance holding the deserialized payload and the bound protocol identifiers.
- **Exceptions**:
  - `BrokerExtractionException`: Thrown if the token is invalid, expired, revoked, has a stale version, or broker metadata is unavailable.
  - `DataDeserializationException`: Thrown if the token is cryptographically valid, but the payload cannot be parsed into `T`.
- **Preconditions**:
  - The token must start with a valid JWT header or a Protocol V4 messageId prefix (`"4:"`).
  - Local reconciliation must be active and not stale (`< 60 minutes` delay).
- **Postconditions**:
  - The local version watermark for the corresponding session `EntryId` is guaranteed to be updated to the verified envelope version.

---

## 2. Model: `VerifiedData<T>`

A generic wrapper carrying the output of a successful verification:

- **`data()`**: Returns the deserialized payload of type `T`.
- **`groupId()`**: Returns the associated group identifier (e.g., user ID).
- **`sequenceId()`**: Returns the unique session sequence identifier (e.g., session UUID).

---

## 3. Code Examples

### Standard Java SE Integration
```java
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.impl.BasicConfigurer;

public class VerifierExample {

    public record UserProfile(String email, String role) {}

    public void processRequest(TokenVerifier verifier, String token) {
        try {
            // Verify and deserialize to UserProfile POJO
            VerifiedData<UserProfile> result = verifier.verify(
                    token,
                    BasicConfigurer.deserializer(UserProfile.class)
            );

            UserProfile profile = result.data();
            System.out.println("Authenticated: " + profile.email());
            System.out.println("Session ID: " + result.sequenceId());

        } catch (BrokerExtractionException e) {
            System.err.println("Authentication failed: " + e.getMessage());
            // Log security rejection code (e.g. V4202, V4201)
        } catch (DataDeserializationException e) {
            System.err.println("Payload parsing failed: " + e.getMessage());
        }
    }
}
```

### Spring Boot Filter (Security Interceptor)
Integrate Veridot directly into your Spring Boot HTTP filter:

```java
import io.github.cyfko.veridot.core.TokenVerifier;
import io.github.cyfko.veridot.core.VerifiedData;
import io.github.cyfko.veridot.core.impl.BasicConfigurer;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public class VeridotSecurityFilter implements Filter {

    private final TokenVerifier tokenVerifier;

    public VeridotSecurityFilter(TokenVerifier tokenVerifier) {
        this.tokenVerifier = tokenVerifier;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String authHeader = req.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing Bearer Token");
            return;
        }

        String token = authHeader.substring(7);
        try {
            // Verify
            VerifiedData<String> verified = tokenVerifier.verify(token, s -> s);
            
            // Bind to request context
            req.setAttribute("veridot.groupId", verified.groupId());
            req.setAttribute("veridot.sequenceId", verified.sequenceId());
            req.setAttribute("veridot.payload", verified.data());
            
            chain.doFilter(request, response);
        } catch (Exception e) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or Revoked Token");
        }
    }
}
```
