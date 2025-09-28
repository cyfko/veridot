Interoperability
================

To ensure compatibility across programming languages, **Veridot** standardizes the events exchanged through a broker. That event should be made of a `key` and a `message` with the following format:  

- **Key:** The broker's event key is a string that represents a unique identifier. This guarantees uniqueness and seamless integration across diverse systems.
- **Message:** The broker's event message is a string that conform to the following structure:  
  - *[token config]*`:`*[Base64 RSA public key]*`:`*[Expiry date seconds]*`:`*[Base64 variant]*

  **Components:**  
  - **[token config]:** Configuration details for the token. Current implementations accept either `jwt` or `id` as valid values.  
  - **[Base64 RSA public key]:** Encoded RSA public key, used for verifying tokens.  
  - **[Expiry date seconds]:** Specifies the expiration time in seconds, enabling automatic removal of outdated entries.  
  - **[Base64 variant]:** Represents additional data related to the `[token config]`.  
    1. For the `jwt` *token config,* this value is optional and may be omitted.  
    2. For the `id` *token config,* this value contains the JWT to be verified or extracted using the public key.

## 🔐 Deterministic Unique ID Generation

**Veridot** uses a **deterministic**, **secure**, and **interoperable** generator of unique identifiers for broker's event keys from integers.

---

### ✨ Goals

- Same integer input → always same output (deterministic)
- Different inputs → different outputs (unique)
- Not guessable (secure via hashing)
- Interoperable across platforms and languages (Java, Python, Rust, Node.js, Go, C#)
- Short enough for use in URLs, database keys, etc.

---

### 🔧 Approach

We use a **SHA-256 hash** of the input integer combined with a static salt, then encode the hash using **Base64 URL-safe encoding**, truncated to **22 characters** (≈132 bits of entropy).

This ensures:
- 🛡️ **Security**: SHA-256 is cryptographically strong.
- 🔁 **Determinism**: Output is stable for the same input.
- 💡 **Compactness**: 22 chars is URL-safe and space-efficient.
- 🧩 **Cross-language compatibility**.

---

### 📌 Formula

```text
input:      int → e.g. 42
salt:       string → e.g. "secure-app"
to hash:    "secure-app-42"
digest:     SHA-256 hash of string
output:     first 22 characters of Base64 URL-encoded hash (no padding)
```

### 🎯 Example

For input `42` and salt `"secure-app"`, the output is:

```sh
ei83cNELeBwvF5e_y50GmQ
```

### 🌐 Language Implementations

All implementations produce the same output for the same inputs.

**Java**
```java
import java.security.MessageDigest;
import java.util.Base64;

public class ShortIdGenerator {
    public static String generateId(int input, String salt) throws Exception {
        String toHash = salt + "-" + input;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(toHash.getBytes("UTF-8"));
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        return b64.substring(0, 22);
    }
}
```

**C#**
```csharp
using System.Security.Cryptography;
using System.Text;

string GenerateShortId(int input, string salt = "secure-app") {
    string toHash = $"{salt}-{input}";
    using var sha = SHA256.Create();
    byte[] hash = sha.ComputeHash(Encoding.UTF8.GetBytes(toHash));
    string b64 = Convert.ToBase64String(hash)
                    .Replace('+', '-')
                    .Replace('/', '_')
                    .TrimEnd('=');
    return b64.Substring(0, 22);
}
```

**Python**
```python
import hashlib, base64

def generate_short_id(n: int, salt: str = "secure-app") -> str:
    to_hash = f"{salt}-{n}"
    digest = hashlib.sha256(to_hash.encode()).digest()
    b64url = base64.urlsafe_b64encode(digest).decode().rstrip("=")
    return b64url[:22]

```

**Node.js**
```javascript
const crypto = require('crypto');

function generateShortId(n, salt = "secure-app") {
    const toHash = `${salt}-${n}`;
    const hash = crypto.createHash('sha256').update(toHash).digest();
    const b64url = hash.toString('base64')
                       .replace(/\+/g, '-')
                       .replace(/\//g, '_')
                       .replace(/=+$/, '');
    return b64url.slice(0, 22);
}
```

**Go**
```go
import (
    "crypto/sha256"
    "encoding/base64"
    "fmt"
)

func GenerateShortID(n int, salt string) string {
    input := fmt.Sprintf("%s-%d", salt, n)
    hash := sha256.Sum256([]byte(input))
    b64 := base64.RawURLEncoding.EncodeToString(hash[:])
    return b64[:22]
}
```

**Rust**
```rust
use sha2::{Sha256, Digest};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};

fn generate_short_id(n: i32, salt: &str) -> String {
    let input = format!("{}-{}", salt, n);
    let mut hasher = Sha256::new();
    hasher.update(input);
    let result = hasher.finalize();
    let b64url = URL_SAFE_NO_PAD.encode(result);
    b64url[..22].to_string()
}
```

## 🚫 Token Revocation
Revoking the validity of a token should be made by sending a broker event with the key as the unique ID of the token to revoke and the associated message with an empty value:
- key == `[The unique ID of the token to revoke]`
- message = `""` (*empty*)

All receiving sites should consider the targeted token as invalid after receiving that broker's event.
