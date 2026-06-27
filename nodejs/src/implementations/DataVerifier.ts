import { Kafka, Consumer, EachMessagePayload } from 'kafkajs';
import jwt, { JwtPayload } from 'jsonwebtoken';
import { open } from 'lmdb';
import crypto from 'crypto';
import { config } from '../config';

/**
 * Represents a key record stored in LMDB.
 * - publicKey: The public key in PEM format.
 * - expiration: Expiration timestamp (in seconds).
 * - keyId: The unique identifier of the key (optional).
 */
export interface KeyRecord {
  kind: string;
  publicKey: string;
  expiration: number;
  variant: string;
}

/**
 * DataVerifier is responsible for verifying JWT tokens signed with ES256,
 * retrieving public keys from Kafka, and storing them locally in LMDB.
 */
export class DataVerifier {
  private db: any;                   // LMDB instance
  private mainConsumer: Consumer;    // Kafka consumer for real-time updates
  private readonly broker: string;   // Kafka broker address
  private readonly kafkaTopic: string; // Kafka topic for public keys
  private isReplaying = false;       // Flag indicating if replay is in progress
  private readonly cleanupIntervalMs: number; // Interval for removing expired keys

  /**
   * Initializes the DataVerifier.
   * @param broker - Kafka broker address (e.g., "localhost:9092").
   * @param kafkaTopic - Kafka topic containing public key messages.
   * @param dbPath - Path for LMDB storage.
   * @param cleanupIntervalMs - Interval in milliseconds for expired key cleanup.
   */
  constructor(
    broker: string = config.broker,
    kafkaTopic: string = config.kafkaTopic,
    dbPath: string = config.dbPath,
    cleanupIntervalMs: number = config.cleanupIntervalMs
  ) {
    this.broker = broker;
    this.kafkaTopic = kafkaTopic;
    this.cleanupIntervalMs = cleanupIntervalMs;

    // Open LMDB database with compression enabled
    this.db = open({ path: dbPath, compression: true });

    // Initialize Kafka client and consumer
    const kafka = new Kafka({ clientId: 'dverify-verifier', brokers: [broker] });
    this.mainConsumer = kafka.consumer({ groupId: 'dverify-verifier-group' });

    // Start the main consumer and subscribe to the topic
    this.initMainConsumer().catch(console.error);

    // Schedule periodic cleanup of expired keys
    setInterval(() => this.cleanExpiredKeys(), this.cleanupIntervalMs);
  }

  /**
   * Initializes the main Kafka consumer to listen for incoming public key messages
   * and store them in LMDB.
   */
  private async initMainConsumer(): Promise<void> {
    // Connect to Kafka consumer
    await this.mainConsumer.connect();

    // Subscribe to the specified topic, reading only new messages (fromBeginning=false)
    await this.mainConsumer.subscribe({ topic: this.kafkaTopic, fromBeginning: false });

    // Start processing messages in real time
    await this.mainConsumer.run({
      eachMessage: async (payload: EachMessagePayload) => {
        await this.handleKafkaMessage(payload);
      },
    });

    console.log('[DataVerifier] Main consumer initialized (fromBeginning=false).');
  }

  /**
   * Handles a received Kafka message by parsing and storing the public key in LMDB.
   * @param message - Kafka message containing the public key data.
   */
  private async handleKafkaMessage({ message }: EachMessagePayload): Promise<void> {
    // Validate the message
    if (!message.key || !message.value) {
      console.warn("[DataVerifier] Invalid Kafka message (missing key or value). Ignoring.");
      return;
    }
    try {
      // Convert key from binary to string
      const key = message.key.toString();
      // Parse JSON value to extract publicKey and expiration
      const parts = message.value.toString().split(':');
      if (parts.length < 4) {
        throw new Error('Invalid Interoperability format');
      }

      const record: KeyRecord = {
        kind: parts[0],
        publicKey: parts[1],
        expiration: Number.parseInt(parts[2]),
        variant: parts[3],
      };

      // Store record in LMDB (key = keyId, value = KeyRecord)
      await this.db.put(key, record);
    } catch (err) {
      console.error("[DataVerifier] Error parsing Kafka message:", err);
    }
  }

  /**
   * Verifies a JWT signed with ES256. If the corresponding public key is not found in LMDB,
   * a Kafka replay is triggered to retrieve the missing key.
   * @param token - The JWT token to verify.
   * @returns The decoded payload if verification is successful.
   * @throws Error if the token is invalid or if the public key cannot be found.
   */
  public async verify<T>(token: string): Promise<T> {
    // Decode the JWT to access its payload
    const decoded = jwt.decode(token, { complete: true });
    if (!decoded || typeof decoded !== 'object' || !('payload' in decoded)) {
      throw new Error('Invalid or malformed token.');
    }
    const payload = decoded.payload as JwtPayload;

    // Ensure the keyId field exists in the payload
    if (!payload.keyId) {
      throw new Error('Missing key ID in token.');
    }

    // Look for the public key in LMDB
    let keyRecord = await this.db.get(payload.keyId);
    if (!keyRecord) {
      // If missing, initiate a replay to retrieve it
      console.log(`[KafkaDataVerifier] Key ${payload.keyId} not found in LMDB, triggering replay.`);
      await this.replayKeysFromBeginning();
      // Retry fetching the key after replay
      keyRecord = await this.db.get(payload.keyId);
      if (!keyRecord) {
        throw new Error(`Public key not found after replay (keyId=${payload.keyId}).`);
      }
    }

    // Ensure the key is not expired
    const now = Math.floor(Date.now() / 1000);
    if (keyRecord.expiration < now) {
      throw new Error(`Public key expired (keyId=${payload.keyId}).`);
    }

    // Perform cryptographic token verification
    try {
      // Convert PEM-formatted key into a Crypto key object
      const publicKeyObj = crypto.createPublicKey(keyRecord.publicKey);

      // Verify the token's signature using ES256
      const verifiedPayload = jwt.verify(token, publicKeyObj, { algorithms: ['ES256'] }) as JwtPayload;

      // Ensure the token contains data
      if (!verifiedPayload.data) {
        throw new Error("Missing data in token.");
      }
      return verifiedPayload.data as T;
    } catch (error) {
      throw new Error(`Cryptographic verification failed: ${error}`);
    }
  }

  /**
   * Triggers a replay of the entire Kafka topic from the beginning
   * to rebuild the LMDB key storage if missing keys are detected.
   */
  private async replayKeysFromBeginning(): Promise<void> {
    // Prevent multiple concurrent replays
    if (this.isReplaying) {
      console.log('[DataVerifier] Replay already in progress, waiting...');
      return;
    }
    this.isReplaying = true;

    // Create a temporary consumer for replaying messages
    const kafka = new Kafka({ clientId: 'dverify-replayer', brokers: [this.broker] });
    const replayConsumer = kafka.consumer({ groupId: 'dverify-replayer-group-' + Date.now() });

    try {
      // Connect the consumer
      await replayConsumer.connect();
      // Subscribe to the topic from the beginning
      await replayConsumer.subscribe({ topic: this.kafkaTopic, fromBeginning: true });

      console.log('[DataVerifier] Starting replay from beginning...');

      // Process all messages and store public keys
      await replayConsumer.run({
        eachMessage: async (payload: EachMessagePayload) => {
          await this.handleKafkaMessage(payload);
        },
      });

      // Wait for a defined period to ensure enough messages are processed
      await new Promise((resolve) => setTimeout(resolve, 5000));
      console.log('[DataVerifier] Replay completed (timeout reached).');

      // Disconnect the temporary consumer
      await replayConsumer.disconnect();
    } catch (err) {
      console.error('[DataVerifier] Error during replay from beginning:', err);
    } finally {
      this.isReplaying = false;
    }
  }

  /**
   * Periodically scans LMDB and removes expired public keys.
   * This method is automatically called at regular intervals.
   */
  private async cleanExpiredKeys(): Promise<void> {
    const now = Math.floor(Date.now() / 1000);
    const keys = this.db.getKeys();

    for (const key of keys) {
      const keyData: KeyRecord = await this.db.get(key);
      if (keyData && keyData.expiration < now) {
        // Remove expired key from LMDB
        await this.db.remove(key);
      }
    }
  }
}
