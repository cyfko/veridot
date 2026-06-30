import { useApp } from '../context/AppContext';
import { Mermaid } from '../components/Mermaid';
import { Admonition } from '../components/Admonition';

const ARCHITECTURE_DIAGRAM = `graph TB
    subgraph "Issuing Service"
        A[DataSigner] --> B[GenericSignerVerifier]
    end
    subgraph "Protocol V4 Layer"
        B --> C[KEY_EPOCH Entry]
        B --> D[LIVENESS=ACTIVE Entry]
        B --> E[CAPABILITY Entry]
    end
    subgraph "Broker (untrusted)"
        F[(Kafka / SQL Store)]
        C --> F
        D --> F
        E --> F
    end
    subgraph "Verifying Service"
        G[Local Cache / RocksDB]
        H[TokenVerifier]
        F --> G
        H --> G
        H --> I[VerifiedData]
    end
    subgraph "TrustRoot (out-of-band)"
        J[PublicKeyTrustRoot / DelegatedTrustRoot]
        H --> J
    end
    style F fill:#fef3c7,stroke:#d97706
    style J fill:#ede9fe,stroke:#7c3aed`;

const SCOPE_DIAGRAM = `graph LR
    G[global] --> S1[site:europe]
    G --> S2[site:us-east]
    S1 --> R1[group:user-alice]
    S1 --> R2[group:user-bob]
    S2 --> R3[group:service-payment]

    style G fill:#ede9fe,stroke:#7c3aed
    style S1 fill:#dbeafe,stroke:#2563eb
    style S2 fill:#dbeafe,stroke:#2563eb
    style R1 fill:#dcfce7,stroke:#16a34a
    style R2 fill:#dcfce7,stroke:#16a34a
    style R3 fill:#dcfce7,stroke:#16a34a`;

export function ConceptsPage() {
  const { language } = useApp();

  const concepts = language === 'en' ? [
    {
      term: 'DataSigner',
      definition: 'A functional interface with one method: sign(Object data, Configurer configurer). Signs a payload into a verifiable token and publishes KEY_EPOCH + LIVENESS=ACTIVE to the broker. Returns a JWT (DIRECT) or a messageId (INDIRECT).',
      package: 'io.github.cyfko.veridot.core',
    },
    {
      term: 'TokenVerifier',
      definition: 'A functional interface with one method: verify(String token, Function deserializer). Runs a 10-step verification pipeline — structural, trust, capability, monotonic version, liveness, and JWT signature checks — then deserializes the payload.',
      package: 'io.github.cyfko.veridot.core',
    },
    {
      term: 'TokenRevoker',
      definition: 'Publishes a signed LIVENESS=REVOKED entry to the broker for a given (groupId, sequenceId) pair, or for all sessions of a group when sequenceId is null.',
      package: 'io.github.cyfko.veridot.core',
    },
    {
      term: 'TokenTracker',
      definition: 'Checks whether at least one LIVENESS=ACTIVE entry exists for a target — a groupId string, a JWT token, or a Protocol V4 messageId — without full verification or deserialization.',
      package: 'io.github.cyfko.veridot.core',
    },
    {
      term: 'GenericSignerVerifier',
      definition: 'The concrete implementation of all four interfaces above. A single instance per service process manages key rotation, liveness renewal, reconciliation, and capacity enforcement.',
      package: 'io.github.cyfko.veridot.core.impl',
    },
    {
      term: 'Broker',
      definition: 'The transport and storage layer. Provides three operations: put(key, bytes), get(key) → bytes, and snapshot(scope) → List<BrokerEntry>. The broker is explicitly NOT trusted — it holds no authority over the validity of the entries it stores.',
      package: 'io.github.cyfko.veridot.core',
    },
    {
      term: 'TrustRoot',
      definition: 'A sealed interface with two permitted implementations. Resolves an issuer string to a TrustIdentity (long-term public key). This is the sole source of cryptographic trust — entirely independent of the broker.',
      package: 'io.github.cyfko.veridot.core',
    },
    {
      term: 'VerifiedData<T>',
      definition: 'An immutable record returned by verify(). Carries three fields: groupId (String), sequenceId (String), and data (T, the deserialized payload).',
      package: 'io.github.cyfko.veridot.core',
    },
    {
      term: 'groupId',
      definition: 'A logical namespace for sessions, typically mapping to a business entity: a user account, a service instance, an API client, or a device. Maps to Protocol V4 scope: "group:<groupId>".',
      package: 'Protocol V4 §2.2',
    },
    {
      term: 'sequenceId',
      definition: 'A unique session within a group. Auto-generated as UUID if not provided at signing time. Corresponds to the "key" field within a group scope in Protocol V4.',
      package: 'Protocol V4 §2.2',
    },
    {
      term: 'messageId',
      definition: 'An opaque reference to a token stored in the broker (INDIRECT mode). Format: "<protoVersion>:<groupId>:<sequenceId>", e.g., "4:user-alice:mobile-v2".',
      package: 'Protocol V4',
    },
    {
      term: 'KEY_EPOCH',
      definition: 'A Protocol V4 entry type (0x01) that distributes the ephemeral public key, its algorithm, and its temporal validity window. Published by the issuer, consumed by all verifiers.',
      package: 'Protocol V4 §5',
    },
    {
      term: 'LIVENESS',
      definition: 'A Protocol V4 entry type (0x04) carrying the active/revoked status of a session. A session is valid only when a fresh LIVENESS=ACTIVE entry exists. Absence defaults to rejection.',
      package: 'Protocol V4 §8',
    },
    {
      term: 'CAPABILITY',
      definition: 'A Protocol V4 entry type (0x02) that grants a specific issuer identity the right to publish entries within one or more scope patterns. Authorization is never established by application logic.',
      package: 'Protocol V4 §6',
    },
  ] : [
    {
      term: 'DataSigner',
      definition: 'Interface fonctionnelle avec une méthode : sign(Object data, Configurer configurer). Signe un payload en token vérifiable et publie KEY_EPOCH + LIVENESS=ACTIVE sur le broker. Retourne un JWT (DIRECT) ou un messageId (INDIRECT).',
      package: 'io.github.cyfko.veridot.core',
    },
    {
      term: 'TokenVerifier',
      definition: 'Interface fonctionnelle avec une méthode : verify(String token, Function deserializer). Exécute un pipeline de vérification en 10 étapes — contrôles structurel, trust, capacité, version monotone, liveness et signature JWT — puis désérialise le payload.',
      package: 'io.github.cyfko.veridot.core',
    },
    {
      term: 'TokenRevoker',
      definition: 'Publie une entrée LIVENESS=REVOKED signée sur le broker pour une paire (groupId, sequenceId) donnée, ou pour toutes les sessions d\'un groupe quand sequenceId est null.',
      package: 'io.github.cyfko.veridot.core',
    },
    {
      term: 'TokenTracker',
      definition: 'Vérifie si au moins une entrée LIVENESS=ACTIVE existe pour une cible — une chaîne groupId, un token JWT, ou un messageId Protocol V4 — sans vérification complète ni désérialisation.',
      package: 'io.github.cyfko.veridot.core',
    },
    {
      term: 'GenericSignerVerifier',
      definition: 'L\'implémentation concrète des quatre interfaces ci-dessus. Une seule instance par processus de service gère la rotation des clés, le renouvellement de liveness, la réconciliation et l\'application de la capacité.',
      package: 'io.github.cyfko.veridot.core.impl',
    },
    {
      term: 'Broker',
      definition: 'La couche de transport et de stockage. Fournit trois opérations : put(key, bytes), get(key) → bytes et snapshot(scope) → List<BrokerEntry>. Le broker n\'est explicitement PAS fiable — il ne détient aucune autorité sur la validité des entrées qu\'il stocke.',
      package: 'io.github.cyfko.veridot.core',
    },
    {
      term: 'TrustRoot',
      definition: 'Interface scellée avec deux implémentations permises. Résout une chaîne issuer en TrustIdentity (clé publique long terme). C\'est l\'unique source de confiance cryptographique — entièrement indépendante du broker.',
      package: 'io.github.cyfko.veridot.core',
    },
    {
      term: 'VerifiedData<T>',
      definition: 'Un record immuable retourné par verify(). Porte trois champs : groupId (String), sequenceId (String) et data (T, le payload désérialisé).',
      package: 'io.github.cyfko.veridot.core',
    },
  ];

  return (
    <div className="space-y-10">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">
          {language === 'en' ? 'Getting Started' : 'Démarrage'}
        </p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Core Concepts' : 'Concepts fondamentaux'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Understanding Veridot\'s conceptual model is the key to using it correctly. This page explains every core term you will encounter throughout the documentation.'
            : 'Comprendre le modèle conceptuel de Veridot est la clé pour l\'utiliser correctement. Cette page explique chaque terme fondamental que vous rencontrerez dans toute la documentation.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Architecture overview' : 'Vue d\'ensemble de l\'architecture'}
        </h2>
        <Mermaid chart={ARCHITECTURE_DIAGRAM} caption={language === 'en' ? 'Veridot system components and data flow' : 'Composants système Veridot et flux de données'} />
        <Admonition type="security" title={language === 'en' ? 'The broker is untrusted by design' : 'Le broker est non fiable par conception'}>
          {language === 'en'
            ? 'A node with full write access to the broker but without the long-term private key corresponding to a TrustRoot-resolvable issuer is structurally incapable of producing an entry that a conforming verifier will accept. This is guaranteed by mandatory signature verification (§3.4) and the monotonic version invariant (§11.1).'
            : 'Un nœud avec un accès complet en écriture au broker mais sans la clé privée long terme correspondant à un émetteur résolvable par TrustRoot est structurellement incapable de produire une entrée qu\'un vérificateur conforme acceptera. Ceci est garanti par la vérification de signature obligatoire (§3.4) et l\'invariant de version monotone (§11.1).'}
        </Admonition>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Scope hierarchy' : 'Hiérarchie des scopes'}
        </h2>
        <Mermaid chart={SCOPE_DIAGRAM} caption={language === 'en' ? 'Protocol V4 scope hierarchy: global → site → group' : 'Hiérarchie des scopes Protocole V4 : global → site → groupe'} />
        <p className="text-slate-600 dark:text-slate-400 text-sm mt-3">
          {language === 'en'
            ? 'Configuration applies hierarchically. A CONFIG entry at the global scope applies to all groups. A site-scoped CONFIG overrides global for all groups in that site. A group-scoped CONFIG is highest priority. Groups declare site membership via the KEY_EPOCH payload.'
            : 'La configuration s\'applique hiérarchiquement. Une entrée CONFIG au scope global s\'applique à tous les groupes. Un CONFIG de scope site remplace le global pour tous les groupes de ce site. Un CONFIG de scope groupe est la priorité la plus haute. Les groupes déclarent leur appartenance au site via le payload KEY_EPOCH.'}
        </p>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Concept glossary' : 'Glossaire des concepts'}
        </h2>
        <div className="space-y-3">
          {concepts.map(c => (
            <div key={c.term} className="border border-slate-200 dark:border-slate-700 rounded-xl p-4 bg-white dark:bg-slate-900">
              <div className="flex flex-wrap items-start gap-2 mb-2">
                <code className="text-violet-700 dark:text-violet-300 font-semibold text-sm bg-violet-50 dark:bg-violet-950/50 px-2 py-0.5 rounded">
                  {c.term}
                </code>
                <span className="text-xs text-slate-400 dark:text-slate-500 font-mono mt-0.5">{c.package}</span>
              </div>
              <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">{c.definition}</p>
            </div>
          ))}
        </div>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Design principles' : 'Principes de conception'}
        </h2>
        <div className="space-y-3">
          {(language === 'en' ? [
            { principle: 'Deny by default', detail: 'Any entry that is malformed, unauthorized, stale, or for which authoritative state cannot be positively established MUST be rejected. Absence of information MUST NOT be interpreted as a permissive state. (Protocol V4 §1.3)' },
            { principle: 'Structural authorization', detail: 'Authorization to act on a scope MUST be established by a verifiable cryptographic CAPABILITY entry, never by an implementation-defined callback or default. (Protocol V4 §1.3, §6)' },
            { principle: 'Monotonic state', detail: 'For any given scope and entry type, state MUST only move forward. The version watermark strictly increases per EntryId. Replay and rollback are structurally impossible for a conforming processor. (Protocol V4 §11)' },
            { principle: 'Positive liveness proof', detail: 'A session is valid only when a fresh, signed ACTIVE attestation exists. Expiration, absence, or invalidity of the attestation all produce the same outcome: rejection. (Protocol V4 §8)' },
            { principle: 'Uniform envelope', detail: 'All information exchanged through the broker uses one canonical signed envelope and one verification pipeline. No entry type may bypass cryptographic verification. (Protocol V4 §1.3, §3)' },
          ] : [
            { principle: 'Refus par défaut', detail: 'Toute entrée malformée, non autorisée, périmée ou pour laquelle l\'état autoritaire ne peut être établi positivement DOIT être rejetée. L\'absence d\'information NE DOIT PAS être interprétée comme un état permissif. (Protocole V4 §1.3)' },
            { principle: 'Autorisation structurelle', detail: 'L\'autorisation d\'agir sur un scope DOIT être établie par une entrée CAPABILITY cryptographique vérifiable, jamais par un callback ou une valeur par défaut définis par l\'implémentation. (Protocole V4 §1.3, §6)' },
            { principle: 'État monotone', detail: 'Pour tout scope et type d\'entrée donnés, l\'état ne peut qu\'avancer. Le watermark de version augmente strictement par EntryId. La relecture et le rollback sont structurellement impossibles pour un processeur conforme. (Protocole V4 §11)' },
            { principle: 'Preuve positive de liveness', detail: 'Une session est valide uniquement quand une attestation ACTIVE fraîche et signée existe. L\'expiration, l\'absence ou l\'invalidité de l\'attestation produisent tous le même résultat : le rejet. (Protocole V4 §8)' },
            { principle: 'Enveloppe uniforme', detail: 'Toutes les informations échangées via le broker utilisent une enveloppe signée canonique et un pipeline de vérification unique. Aucun type d\'entrée ne peut contourner la vérification cryptographique. (Protocole V4 §1.3, §3)' },
          ]).map(p => (
            <div key={p.principle} className="border-l-4 border-violet-400 pl-4 py-1">
              <p className="font-semibold text-slate-900 dark:text-white text-sm">{p.principle}</p>
              <p className="text-sm text-slate-600 dark:text-slate-400 mt-1">{p.detail}</p>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
