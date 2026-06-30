import { useApp } from '../context/AppContext';

export function GlossaryPage() {
  const { language } = useApp();

  const terms = language === 'en' ? [
    { term: 'Broker', def: 'A transport and storage component providing entry persistence, retrieval by key, and full-scope enumeration. The broker is responsible for delivery and durability; it is NOT a trusted component and holds no authority over the validity of any entry it stores or transmits. (Protocol V4 §2.2)' },
    { term: 'CAPABILITY', def: 'A Protocol V4 entry type (0x02). A signed grant authorizing a specific issuer identity to publish entries within one or more scope patterns. Authorization is established exclusively by CAPABILITY entries — never by application logic.' },
    { term: 'Clock drift tolerance', def: 'A fixed ±5 minute (300,000 ms) window applied to temporal validity checks. A KEY_EPOCH entry is considered active if now ≥ validFrom - 300,000ms. (Protocol V4 §5.3, §13.1)' },
    { term: 'CONFIG', def: 'A Protocol V4 entry type (0x03). A signed configuration record for a scope (group, site, or global). Carries maxSessions, eviction policy, and default TTL. Requires a valid CAPABILITY entry for the issuer.' },
    { term: 'COMPACT_SIG flag', def: 'Bit 0 of the flags field in the envelope header. MUST be 1 if and only if sigAlg = Ed25519 (0x04). Inconsistency results in rejection with V4005.' },
    { term: 'DataSigner', def: 'The Veridot interface for signing payloads into tokens. One method: sign(Object data, Configurer configurer). Implemented by GenericSignerVerifier.' },
    { term: 'DelegatedTrustRoot', def: 'A TrustRoot implementation where signature verification is delegated to an external KMS (Vault, AWS KMS, GCP KMS, HSM). The long-term private key never leaves the KMS.' },
    { term: 'DIRECT mode', def: 'A DistributionMode in which the signed JWT is returned directly to the caller of sign().' },
    { term: 'DistributionMode', def: 'Enum determining how the signed token is delivered after sign(): DIRECT (return JWT) or INDIRECT (return messageId).' },
    { term: 'Ephemeral key pair', def: 'A short-lived asymmetric key pair generated per signing operation or key rotation period. The private key signs the JWT; the public key is published in the KEY_EPOCH entry. The default algorithm is Ed25519.' },
    { term: 'EntryId', def: 'The triple (scope, entryType, key) uniquely identifying an entry\'s logical position. The broker storage key is derived from EntryId via: scope || 0x00 || entryType || 0x00 || key. (Protocol V4 §3.3)' },
    { term: 'EvictionPolicy', def: 'Enum: FIFO, LIFO, LRU, REJECT. Determines which session is evicted when maxSessions is reached.' },
    { term: 'FENCE', def: 'A Protocol V4 entry type (0x05). A signed, monotonically increasing counter scoped to a single scope, used to totally order capacity-affecting mutations across concurrent processors. (Protocol V4 §9)' },
    { term: 'GenericSignerVerifier', def: 'The primary implementation class in veridot-core. Implements DataSigner, TokenVerifier, TokenRevoker, TokenTracker, and AutoCloseable.' },
    { term: 'groupId', def: 'A logical namespace aggregating related sessions, typically mapping to a business entity (user ID, service ID, API client). Maps to the Protocol V4 scope "group:<groupId>".' },
    { term: 'INDIRECT mode', def: 'A DistributionMode in which the JWT is stored in the broker. The caller receives a messageId instead of the JWT.' },
    { term: 'issuer', def: 'The long-term identifier of the signing authority. Carried in every envelope. Resolved by the TrustRoot to obtain the long-term public key for signature verification.' },
    { term: 'KEY_EPOCH', def: 'A Protocol V4 entry type (0x01). Distributes the ephemeral public key, its algorithm, and its temporal validity window. Published by the issuer after each sign() call.' },
    { term: 'LIVENESS', def: 'A Protocol V4 entry type (0x04). A signed attestation of a session\'s ACTIVE or REVOKED status. A session is valid only when a fresh LIVENESS=ACTIVE entry exists.' },
    { term: 'messageId', def: 'An opaque reference to a token stored in the broker (INDIRECT mode). Format: "<protoVersion>:<groupId>:<sequenceId>". Example: "4:user-alice:mobile-v2".' },
    { term: 'Monotonic version invariant', def: 'For every EntryId, a conforming processor maintains the highest version it has accepted. Incoming entries are accepted only if their version is strictly greater than the recorded value. (Protocol V4 §11.1)' },
    { term: 'Positive liveness proof', def: 'A session is valid only when a fresh, signed ACTIVE attestation exists. Absence, invalidity, or expiry of the attestation all produce the same outcome: rejection. (Protocol V4 §8.3)' },
    { term: 'Processor', def: 'A software component implementing Protocol V4. Performs issuance (sign/publish) and/or verification (read/validate). GenericSignerVerifier is the Java processor.' },
    { term: 'PublicKeyTrustRoot', def: 'A TrustRoot implementation where you load the public key and Veridot performs signature verification. Suitable for development; for production prefer DelegatedTrustRoot.' },
    { term: 'Reconciliation', def: 'A periodic operation that re-reads the full scope snapshot from the broker and updates local version watermarks. Closes the gap from missed incremental deliveries. (Protocol V4 §11.4)' },
    { term: 'scope', def: 'A typed hierarchical namespace an entry applies to. One of: "group:<groupId>", "site:<siteId>", or "global". (Protocol V4 §2.2, §3.5)' },
    { term: 'sequenceId', def: 'A unique session identifier within a group. Auto-generated as UUID if not provided at signing time. Corresponds to the "key" field in the broker storage key.' },
    { term: 'siteId', def: 'A logical partition enabling shared configuration across multiple groups. Groups declare site membership via the KEY_EPOCH payload.' },
    { term: 'SNAPSHOT_MARKER', def: 'A Protocol V4 entry type (0x06). A signed record that a complete point-in-time snapshot of a scope was committed to the broker. (Protocol V4 §11.5)' },
    { term: 'Token', def: 'In Veridot, any signed object whose validity can be verified by the protocol. Typically a JWT, but also any signed data with a corresponding KEY_EPOCH entry in the broker.' },
    { term: 'TokenRevoker', def: 'The Veridot interface for revoking sessions. One method: revoke(String groupId, String sequenceId). Null sequenceId revokes all sessions in the group.' },
    { term: 'TokenTracker', def: 'The Veridot interface for checking token activity. One method: hasActiveToken(Object target). Target can be a groupId, JWT, or messageId.' },
    { term: 'TokenVerifier', def: 'The Veridot interface for verifying tokens. One method: verify(String token, Function deserializer). Accepts both JWTs and messageIds.' },
    { term: 'TrustRoot', def: 'A sealed interface resolving long-term issuer identifiers to public keys. The sole source of cryptographic trust — independent of the broker. (Protocol V4 §2.2)' },
    { term: 'VerifiedData<T>', def: 'An immutable record returned by verify(). Fields: groupId (String), sequenceId (String), data (T).' },
    { term: 'Version', def: 'A 64-bit unsigned integer carried by every entry, strictly increasing per EntryId. Establishes the total order for monotonic state resolution. Minimum valid value: 1.' },
    { term: 'WatermarkStore', def: 'An interface for persisting version watermarks across restarts. Implementations: FileWatermarkStore, or a broker that also implements this interface (e.g., KafkaBroker).' },
  ] : [
    { term: 'Broker', def: 'Composant de transport et de stockage fournissant la persistance des entrées, la récupération par clé et l\'énumération de scope complet. Le broker est responsable de la livraison et de la durabilité ; il N\'est PAS un composant fiable et ne détient aucune autorité sur la validité des entrées qu\'il stocke ou transmet. (Protocole V4 §2.2)' },
    { term: 'CAPABILITY', def: 'Type d\'entrée Protocole V4 (0x02). Une autorisation signée autorisant une identité d\'émetteur spécifique à publier des entrées dans un ou plusieurs patterns de scope. L\'autorisation est établie exclusivement par les entrées CAPABILITY — jamais par la logique applicative.' },
    { term: 'Tolérance de dérive d\'horloge', def: 'Une fenêtre fixe de ±5 minutes (300 000 ms) appliquée aux vérifications de validité temporelle. (Protocole V4 §5.3, §13.1)' },
    { term: 'CONFIG', def: 'Type d\'entrée Protocole V4 (0x03). Un enregistrement de configuration signé pour un scope (groupe, site ou global). Porte maxSessions, la politique d\'éviction et le TTL par défaut.' },
    { term: 'DataSigner', def: 'L\'interface Veridot pour signer des payloads en tokens. Une méthode : sign(Object data, Configurer configurer). Implémentée par GenericSignerVerifier.' },
    { term: 'DelegatedTrustRoot', def: 'Implémentation de TrustRoot où la vérification de signature est déléguée à un KMS externe (Vault, AWS KMS, GCP KMS, HSM). La clé privée long terme ne quitte jamais le KMS.' },
    { term: 'Mode DIRECT', def: 'DistributionMode dans lequel le JWT signé est retourné directement à l\'appelant de sign().' },
    { term: 'EvictionPolicy', def: 'Enum : FIFO, LIFO, LRU, REJECT. Détermine quelle session est évincée quand maxSessions est atteint.' },
    { term: 'GenericSignerVerifier', def: 'La classe d\'implémentation principale dans veridot-core. Implémente DataSigner, TokenVerifier, TokenRevoker, TokenTracker et AutoCloseable.' },
    { term: 'groupId', def: 'Namespace logique regroupant les sessions associées, mappant typiquement vers une entité métier (ID utilisateur, ID service, client API). Correspond au scope Protocole V4 "group:<groupId>".' },
    { term: 'Mode INDIRECT', def: 'DistributionMode dans lequel le JWT est stocké dans le broker. L\'appelant reçoit un messageId au lieu du JWT.' },
    { term: 'KEY_EPOCH', def: 'Type d\'entrée Protocole V4 (0x01). Distribue la clé publique éphémère, son algorithme et sa fenêtre de validité temporelle. Publié par l\'émetteur après chaque appel sign().' },
    { term: 'LIVENESS', def: 'Type d\'entrée Protocole V4 (0x04). Une attestation signée du statut ACTIVE ou REVOKED d\'une session. Une session est valide uniquement quand une entrée LIVENESS=ACTIVE fraîche existe.' },
    { term: 'messageId', def: 'Référence opaque à un token stocké dans le broker (mode INDIRECT). Format : "<protoVersion>:<groupId>:<sequenceId>". Exemple : "4:user-alice:mobile-v2".' },
    { term: 'Invariant de version monotone', def: 'Pour chaque EntryId, un processeur conforme maintient la version la plus élevée qu\'il a acceptée. Les entrées entrantes sont acceptées uniquement si leur version est strictement supérieure à la valeur enregistrée. (Protocole V4 §11.1)' },
    { term: 'TrustRoot', def: 'Interface scellée résolvant les identifiants d\'émetteur long terme en clés publiques. L\'unique source de confiance cryptographique — indépendante du broker. (Protocole V4 §2.2)' },
    { term: 'VerifiedData<T>', def: 'Record immuable retourné par verify(). Champs : groupId (String), sequenceId (String), data (T).' },
  ];

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">Reference</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Glossary' : 'Glossaire'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Definitions for all terms used in the Veridot documentation and Protocol V4 specification.'
            : 'Définitions de tous les termes utilisés dans la documentation Veridot et la spécification du Protocole V4.'}
        </p>
      </div>

      <div className="space-y-2">
        {terms.map(t => (
          <div key={t.term} id={t.term.toLowerCase().replace(/[^a-z0-9]/g, '-')}
            className="border border-slate-200 dark:border-slate-700 rounded-xl p-4 bg-white dark:bg-slate-900">
            <code className="text-violet-700 dark:text-violet-300 font-semibold text-sm">{t.term}</code>
            <p className="text-sm text-slate-600 dark:text-slate-400 mt-2 leading-relaxed">{t.def}</p>
          </div>
        ))}
      </div>
    </div>
  );
}
