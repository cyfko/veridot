import { useApp } from '../../context/AppContext';
import { Mermaid } from '../../components/Mermaid';
import { Admonition } from '../../components/Admonition';
import { CodeBlock } from '../../components/CodeBlock';

const VERIFY_SEQUENCE = `sequenceDiagram
    participant App as Application
    participant GSV as GenericSignerVerifier
    participant Cache as Local Cache / RocksDB
    participant TR as TrustRoot
    participant EV as EntryVerifier

    App->>GSV: verify(token, deserializer)

    Note over GSV: Step 1 - Resolve token format
    GSV->>GSV: isJwt(token) or isMessageId(token)?
    GSV->>GSV: extract messageId from JWT sub claim

    Note over GSV: Steps 2-3 - Retrieve + structural validation
    GSV->>Cache: get(KEY_EPOCH storage key)
    Cache-->>GSV: envelope bytes
    GSV->>EV: parse envelope (magic, protoVersion, entryType...)
    EV-->>GSV: validated Envelope or V4001/V4002/V4003/V4004/V4005/V4006/V4007

    Note over GSV: Step 4 - Trust + Signature validation
    GSV->>TR: resolve(issuer)
    TR-->>GSV: TrustIdentity (public key)
    GSV->>EV: verify signature over canonical bytes
    EV-->>GSV: valid or V4101

    Note over GSV: Step 5 - Capability check
    GSV->>Cache: get CAPABILITY entry for (scope, signerId)
    Cache-->>GSV: CAPABILITY envelope
    GSV->>EV: validateCapability chain (maxDelegationDepth, validUntil)
    EV-->>GSV: authorized or V4102/V4103/V4104

    Note over GSV: Step 6 - Monotonic version check
    GSV->>GSV: watermark.current(epochId) < entry.version?
    GSV-->>GSV: accepted or V4201

    Note over GSV: Step 7 - Temporal validity
    GSV->>GSV: now in [validFrom-5min, validUntil)?
    GSV-->>GSV: valid or V4203

    Note over GSV: Step 8 - Liveness check
    GSV->>Cache: get LIVENESS entry for (scope, sequenceId)
    Cache-->>GSV: LIVENESS envelope
    GSV->>EV: status=ACTIVE and not expired?
    EV-->>GSV: live or V4202

    Note over GSV: Step 9 - JWT signature (ephemeral key)
    GSV->>GSV: verify JWT alg header vs KEY_EPOCH alg
    GSV->>GSV: verify JWT signature with ephemeral publicKey
    GSV-->>GSV: valid or V4204/BrokerExtractionException

    Note over GSV: Step 10 - Deserialize payload
    GSV->>App: deserializer.apply(data)
    App-->>GSV: T

    GSV-->>App: VerifiedData<T>`;

const REVOCATION_SEQUENCE = `sequenceDiagram
    participant A as Auth Service
    participant Broker as Broker (Kafka)
    participant SvcB as Service B
    participant SvcC as Service C

    A->>Broker: publish LIVENESS=REVOKED (signed)
    Note over Broker: Persists revoked entry

    Broker-->>SvcB: deliver LIVENESS=REVOKED
    Broker-->>SvcC: deliver LIVENESS=REVOKED

    Note over SvcB,SvcC: Version watermark updated
    Note over SvcB,SvcC: Session marked REVOKED locally

    SvcB->>SvcB: verify(token) → Step 8 → REVOKED → V4202 → reject
    SvcC->>SvcC: verify(token) → Step 8 → REVOKED → V4202 → reject`;

const CLOCK_DRIFT = `// Clock drift tolerance: ±5 minutes (300,000 ms) — Protocol V4 §13.1
//
// KEY_EPOCH active condition (§5.3):
// 1. now >= validFrom - 300_000ms    (±5 min tolerance for start)
// 2. now < validUntil                (hard end of epoch)
// 3. No REVOKED LIVENESS entry with version >= current
//
// LIVENESS active condition (§8.3):
// 1. status = ACTIVE
// 2. now < (asOf + validDuration)  (fresh attestation check)
// 3. Clock drift tolerance applied`;

export function VerificationFlowPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">
          {language === 'en' ? 'Protocol V4' : 'Protocole V4'}
        </p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Verification Flow' : 'Flux de vérification'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'A detailed walkthrough of the 10-step verification pipeline defined in Protocol V4 §5.4.'
            : 'Un aperçu détaillé du pipeline de vérification en 10 étapes défini dans le Protocole V4 §5.4.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Complete verification sequence' : 'Séquence de vérification complète'}
        </h2>
        <Mermaid chart={VERIFY_SEQUENCE} caption={language === 'en' ? 'Protocol V4 §5.4 — 10-step verification pipeline' : 'Protocole V4 §5.4 — Pipeline de vérification en 10 étapes'} />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Step-by-step breakdown' : 'Décomposition étape par étape'}
        </h2>
        <div className="space-y-3">
          {(language === 'en' ? [
            { n: 1, title: 'Resolve token format', detail: 'Determines whether the input is a JWT (DIRECT mode) or a messageId (INDIRECT mode). For JWTs, the messageId is extracted from the "sub" claim. For messageIds, the format "4:<groupId>:<sequenceId>" is parsed.' },
            { n: 2, title: 'Retrieve KEY_EPOCH entry', detail: 'Computes the storage key from (scope, KEY_EPOCH, sequenceId) and retrieves the envelope bytes from the local cache (RocksDB for Kafka broker, or DB replica). This is a local read — no network call.' },
            { n: 3, title: 'Structural validation', detail: 'Validates magic (0x56 0x44), protoVersion (0x04), entryType, flags, all length fields (scopeLen, keyLen, issuerLen, payloadLen), and scope grammar. Any violation produces a specific error code (V4001–V4007).' },
            { n: 4, title: 'Trust + signature validation', detail: 'Resolves the issuer field through the TrustRoot. Verifies the envelope signature over the canonical bytes (§3.4) using the resolved long-term public key. Failure: V4101.' },
            { n: 5, title: 'Capability chain verification', detail: 'Confirms the issuer holds a valid, unexpired CAPABILITY entry whose scopePatterns cover the entry\'s scope. Verifies the full delegation chain (maxDelegationDepth). Failure: V4102, V4103, V4104.' },
            { n: 6, title: 'Monotonic version check', detail: 'Verifies that the entry\'s version is strictly greater than the recorded watermark for this EntryId. Prevents replay attacks and rollback. Failure: V4201.' },
            { n: 7, title: 'Temporal validity check', detail: 'Verifies now ≥ validFrom - 300,000ms AND now < validUntil. The ±5 minute clock drift tolerance is fixed by the protocol (§13.1). Failure: V4203.' },
            { n: 8, title: 'Liveness check', detail: 'Retrieves the LIVENESS entry for this (scope, sequenceId). Verifies status=ACTIVE and the attestation is not expired. Absence of the entry, REVOKED status, or expiry all produce the same outcome: V4202. Failure: V4202.' },
            { n: 9, title: 'JWT cryptographic signature', detail: 'Verifies that the JWT "alg" header matches the Algorithm declared in KEY_EPOCH (prevents algorithm confusion attacks — §13.1). Verifies the JWT signature using the ephemeral public key from KEY_EPOCH.' },
            { n: 10, title: 'Payload deserialization', detail: 'Applies the caller-provided deserializer function to the raw "data" claim from the JWT payload. Throws DataDeserializationException if the deserializer fails. This is the only step that can throw DataDeserializationException.' },
          ] : [
            { n: 1, title: 'Résoudre le format du token', detail: 'Détermine si l\'entrée est un JWT (mode DIRECT) ou un messageId (mode INDIRECT). Pour les JWT, le messageId est extrait de la revendication "sub". Pour les messageIds, le format "4:<groupId>:<sequenceId>" est analysé.' },
            { n: 2, title: 'Récupérer l\'entrée KEY_EPOCH', detail: 'Calcule la clé de stockage depuis (scope, KEY_EPOCH, sequenceId) et récupère les octets d\'enveloppe depuis le cache local (RocksDB pour le broker Kafka, ou réplica DB). C\'est une lecture locale — pas d\'appel réseau.' },
            { n: 3, title: 'Validation structurelle', detail: 'Valide magic (0x56 0x44), protoVersion (0x04), entryType, flags, tous les champs de longueur (scopeLen, keyLen, issuerLen, payloadLen) et la grammaire du scope. Toute violation produit un code d\'erreur spécifique (V4001–V4007).' },
            { n: 4, title: 'Validation de trust + signature', detail: 'Résout le champ issuer via la TrustRoot. Vérifie la signature de l\'enveloppe sur les octets canoniques (§3.4) en utilisant la clé publique long terme résolue. Échec : V4101.' },
            { n: 5, title: 'Vérification de la chaîne de capacité', detail: 'Confirme que l\'émetteur détient une entrée CAPABILITY valide et non expirée dont les scopePatterns couvrent le scope de l\'entrée. Vérifie la chaîne de délégation complète (maxDelegationDepth). Échec : V4102, V4103, V4104.' },
            { n: 6, title: 'Vérification du watermark de version monotone', detail: 'Vérifie que la version de l\'entrée est strictement supérieure au watermark enregistré pour cet EntryId. Prévient les attaques par relecture et rollback. Échec : V4201.' },
            { n: 7, title: 'Vérification de validité temporelle', detail: 'Vérifie maintenant ≥ validFrom - 300 000 ms ET maintenant < validUntil. La tolérance ±5 minutes de dérive d\'horloge est fixée par le protocole (§13.1). Échec : V4203.' },
            { n: 8, title: 'Vérification de liveness', detail: 'Récupère l\'entrée LIVENESS pour ce (scope, sequenceId). Vérifie status=ACTIVE et que l\'attestation n\'est pas expirée. L\'absence de l\'entrée, le statut REVOKED ou l\'expiration produisent tous le même résultat : V4202.' },
            { n: 9, title: 'Signature cryptographique JWT', detail: 'Vérifie que l\'en-tête "alg" du JWT correspond à l\'Algorithm déclaré dans KEY_EPOCH (prévient les attaques par confusion d\'algorithme — §13.1). Vérifie la signature JWT avec la clé publique éphémère de KEY_EPOCH.' },
            { n: 10, title: 'Désérialisation du payload', detail: 'Applique la fonction de désérialisation fournie par l\'appelant à la revendication brute "data" du payload JWT. Lance DataDeserializationException si le désérialiseur échoue. C\'est la seule étape qui peut lancer DataDeserializationException.' },
          ]).map(step => (
            <div key={step.n} className="flex gap-4 border border-slate-200 dark:border-slate-700 rounded-xl p-4 bg-white dark:bg-slate-900">
              <div className="flex-shrink-0 h-7 w-7 rounded-full bg-violet-100 dark:bg-violet-900/50 text-violet-700 dark:text-violet-300 text-sm font-bold flex items-center justify-center">
                {step.n}
              </div>
              <div>
                <p className="font-semibold text-slate-900 dark:text-white text-sm">{step.title}</p>
                <p className="text-sm text-slate-600 dark:text-slate-400 mt-1 leading-relaxed">{step.detail}</p>
              </div>
            </div>
          ))}
        </div>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Revocation propagation' : 'Propagation de la révocation'}
        </h2>
        <Mermaid chart={REVOCATION_SEQUENCE} caption={language === 'en' ? 'Revocation propagates via the broker to all verifiers' : 'La révocation se propage via le broker vers tous les vérificateurs'} />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Clock drift handling' : 'Gestion de la dérive d\'horloge'}
        </h2>
        <CodeBlock code={CLOCK_DRIFT} language="text" title="Clock drift tolerance — Protocol V4 §13.1" showLineNumbers={false} />
        <Admonition type="info" title={language === 'en' ? 'Timestamps are advisory only' : 'Les timestamps sont uniquement consultatifs'}>
          {language === 'en'
            ? 'The timestamp field in the envelope header is for human-readable auditing only. It MUST NOT be used for ordering or conflict resolution — which rely exclusively on the version (u64) field.'
            : 'Le champ timestamp dans l\'en-tête de l\'enveloppe est uniquement pour l\'audit lisible par l\'homme. Il NE DOIT PAS être utilisé pour l\'ordre ou la résolution de conflits — qui reposent exclusivement sur le champ version (u64).'}
        </Admonition>
      </section>
    </div>
  );
}
