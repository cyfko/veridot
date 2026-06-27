# 📦 dverify

A robust TypeScript module for signing and verifying data using JWT and ECDSA keys distributed via Kafka, with LMDB persistence and automatic key rotation.

---

## ✨ Features

- 🔐 **JWT Signing & Verification** using ES256 (ECDSA)
- 🔁 **Automatic Key Rotation**
- 📬 **Public Key Distribution** via Kafka
- 🧠 **Fast and Persistent Storage** using LMDB
- 📡 **Offline Key Replay Support** (via Kafka fromBeginning)
- ⚙️ **Environment-Based Configuration** with sane defaults

---

## 📦 Installation

```bash
npm install dverify
```

or

```bash
pnpm add dverify
```

---

---

## 🛠️ Usage

The module provides two usage modes:

### ✅ **Easy Mode (`DVerify`)**

Ideal for simple cases, with built-in signer and verifier:

```ts
import { DVerify } from 'dverify';

const dverify = new DVerify();

// Sign data
const { token } = await dverify.sign({ userId: 123 }, 1200);

// Verify token
const result = await dverify.verify(token);
console.log(result.valid, result.data);
```

### ⚙️ **Advanced Mode (Direct use of signer/verifier)**

Directly handle signing and verifying separately, for advanced scenarios (e.g., microservices):

**Signing:**

```ts
import { DataSigner } from 'dverify';

const signer = new DataSigner();

// Sign data directly
const token = await signer.sign({ orderId: 'xyz' }, 3600);
console.log(token);
```

**Verifying:**

```ts
import { DataVerifier } from 'dverify';

const verifier = new DataVerifier();

// Verify token directly
try {
  const data = await verifier.verify(token);
  console.log('Data:', data);
} catch (error) {
  console.error('Invalid token:', error);
}
```

---

## 🧪 API

### Easy Mode (`DVerify` class)

- **`sign(message: Record<string, any>, duration?: number): Promise<{ token: string }>`**
    - Signs the data and returns a JWT.
    - `message`: JSON object to sign.
    - `duration`: Token validity in seconds (default: `1400`).

- **`verify<T>(token: string): Promise<{ valid: boolean; data: T }>`**
    - Verifies the JWT and returns the decoded data.

### Advanced Mode (Separate classes)

- **`DataSigner`** (signing only)
    - `sign<T>(data: T, duration: number): Promise<string>`

- **`DataVerifier`** (verification only)
    - `verify<T>(token: string): Promise<T>`

---

## ⚙️ Configuration

The module reads from your environment variables, but provides fallbacks:

| Variable                          | Description                                | Default               |
|----------------------------------|--------------------------------------------|-----------------------|
| `KAFKA_BROKER`                   | Kafka broker URL                           | `localhost:9093`      |
| `DVERIFY_KAFKA_TOPIC`            | Kafka topic for key exchange               | `public_keys_topic`   |
| `DVERIFY_DB_PATH`                | Path for LMDB storage                      | `./signer-db`         |
| `DVERIFY_KEY_ROTATION_MS`        | Key rotation interval (ms)                 | `3600000` (1h)        |
| `DVERIFY_CLEANUP_INTERVAL_MS`    | LMDB cleanup interval for expired keys     | `1800000` (30min)     |

Use a `.env` file in your consuming project:

```
KAFKA_BROKER=localhost:9092
DVERIFY_KAFKA_TOPIC=your_topic
DVERIFY_DB_PATH=./data/dverify
```

---

## 📂 Project Structure

```
src/
├── implementations/
│   ├── DataSigner.ts       // Kafka producer + key rotation
│   └── DataVerifier.ts     // Kafka consumer + JWT verification
├── interfaces/                    // Type-safe abstractions
├── config.ts                      // Environment configuration
├── Dverify.ts                     // Main public API
├── index.ts                       // Package entry point
```

---

## 📌 Requirements

- Node.js >= 16
- Kafka cluster running
- Consumer project should load `.env` before usage


---

## 🔐 Security Considerations

- Uses ES256 (ECDSA with P-256 curve)
- All public keys are stored and verified from LMDB
- Only valid keys within the expiration window are accepted

---

## 🧑‍💻 Author

**Darlinho T.** – [LinkedIn](https://www.linkedin.com/in/hyacinthe-darlin-teuma-nougosso-546521206) • [GitHub](https://github.com/darlinho)
_Contributions and feedback are welcome!_

---

## 📄 License

MIT © 2025 - Darlinho