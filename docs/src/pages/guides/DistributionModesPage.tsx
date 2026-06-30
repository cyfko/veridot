import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';
import { Mermaid } from '../../components/Mermaid';

const DIRECT_CODE = `// DIRECT mode: returns the fully signed JWT token string
String token = signer.sign(userClaims,
    BasicConfigurer.builder()
        .groupId("user-102")
        .distribution(DistributionMode.DIRECT) // default
        .validity(3600)
        .build()
);`;

const INDIRECT_CODE = `// INDIRECT mode: returns the Protocol V4 messageId
String messageId = signer.sign(userClaims,
    BasicConfigurer.builder()
        .groupId("user-102")
        .distribution(DistributionMode.INDIRECT)
        .validity(3600)
        .build()
);`;

const SECURE_PAYLOAD_UNICAST_CODE = `// SECURE_PAYLOAD: Confidential Unicast / Multicast mode (Encrypted payload)
List<String> authorizedRecipients = List.of("processor-alice", "processor-bob");
byte[] sensitiveData = "Confidential customer details".getBytes(StandardCharsets.UTF_8);

String secureToken = signer.publishSecurePayload(
    Scope.group("confidential-docs"),
    "doc-123",
    sensitiveData,
    authorizedRecipients,
    "application/json"
);

// Decrypting at recipient side:
VerifiedData<byte[]> verified = verifier.verify(secureToken, bytes -> bytes);
byte[] clearData = verified.data();`;

const SECURE_PAYLOAD_BROADCAST_CODE = `// SECURE_PAYLOAD: Broadcast Public mode (Plaintext payload, signed envelope)
byte[] publicData = "Public announcement".getBytes(StandardCharsets.UTF_8);

String broadcastToken = signer.publishSecurePayload(
    Scope.group("public-news"),
    "announcement-45",
    publicData,
    null, // null/empty list implies Broadcast Public mode
    "text/plain"
);

// Verifying at recipient side:
VerifiedData<byte[]> verified = verifier.verify(broadcastToken, bytes -> bytes);
byte[] clearData = verified.data();`;

const CLASSIC_FLOW_CHART = `sequenceDiagram
    autonumber
    actor Client
    participant Emitter as Emitter Node
    participant Broker as Untrusted Broker
    participant Verifier as Verifier Node
    participant Attacker as Passive Listener

    Emitter->>Broker: Publish full JWT containing plaintext sensitive data
    Note over Broker: JWT is stored in cleartext!
    rect rgb(254, 226, 226)
        Attacker->>Broker: Read metadata / logs
        Note over Attacker: Leaks sensitive profile / role claims!
    end
    Emitter->>Client: Send lightweight Token (messageId reference)
    Client->>Verifier: Present messageId reference
    Verifier->>Broker: Fetch full JWT using messageId
    Broker-->>Verifier: Return full JWT
    Verifier->>Verifier: Validate signature & authenticate Client`;

const SECURE_FLOW_CHART = `sequenceDiagram
    autonumber
    actor Client
    participant Emitter as Emitter Node
    participant Broker as Untrusted Broker
    participant Verifier as Verifier Node
    participant Attacker as Passive Listener

    Emitter->>Emitter: Generate ephemeral key_sym & encrypt payload (AES-GCM)
    Emitter->>Emitter: Encapsulate key_sym with Verifier's public key (RSA/ECDH)
    Emitter->>Broker: Publish signed SECURE_PAYLOAD envelope
    Note over Broker: Data is encrypted, key is encapsulated!
    rect rgb(209, 250, 229)
        Attacker->>Broker: Read metadata / logs
        Note over Attacker: Only sees ciphertext & encrypted key blocks (Access Denied)
    end
    Emitter->>Client: Send lightweight Token (SECURE_PAYLOAD pointer)
    Client->>Verifier: Present SECURE_PAYLOAD pointer
    Verifier->>Broker: Fetch SECURE_PAYLOAD envelope
    Broker-->>Verifier: Return envelope
    Verifier->>Verifier: Verify envelope signature
    Verifier->>Verifier: Decrypt key_sym using its private key
    Verifier->>Verifier: Decrypt payload with key_sym (AES-GCM)`;

export function DistributionModesPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8 max-w-4xl mx-auto">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">
          {language === 'en' ? 'Guides · Architecture & Security' : 'Guides · Architecture & Sécurité'}
        </p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Token Distribution Modes' : 'Modes de Distribution des Tokens'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'A complete architectural guide to selecting and securing DIRECT and INDIRECT token distribution modes.'
            : 'Un guide d\'architecture complet pour sélectionner et sécuriser les modes de distribution DIRECT et INDIRECT.'}
        </p>
      </div>

      {/* Comparison section */}
      <div className="grid sm:grid-cols-2 gap-6 my-6">
        <div className="border border-slate-200 dark:border-slate-800 rounded-xl p-5 bg-slate-50/50 dark:bg-slate-900/20">
          <h3 className="text-violet-700 dark:text-violet-400 font-bold text-base mb-2">
            {language === 'en' ? 'Direct Mode (By-Value)' : 'Mode Direct (Par Valeur)'}
          </h3>
          <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed mb-3">
            {language === 'en'
              ? 'The token (JWT) is sent directly to the client. The client holds the full set of claims and credentials.'
              : 'Le token (JWT) est envoyé directement au client. Le client détient l\'ensemble des privilèges et profils.'}
          </p>
          <ul className="text-xs space-y-1 text-slate-500 list-disc list-inside">
            <li>{language === 'en' ? 'Zero broker overhead' : 'Zéro dépendance envers le broker'}</li>
            <li>{language === 'en' ? 'Higher network consumption' : 'Consommation réseau plus élevée'}</li>
            <li>{language === 'en' ? 'Client sees all claims' : 'Le client voit toutes les données'}</li>
          </ul>
        </div>

        <div className="border border-slate-200 dark:border-slate-800 rounded-xl p-5 bg-slate-50/50 dark:bg-slate-900/20">
          <h3 className="text-violet-700 dark:text-violet-400 font-bold text-base mb-2">
            {language === 'en' ? 'Indirect Mode (By-Reference)' : 'Mode Indirect (Par Référence)'}
          </h3>
          <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed mb-3">
            {language === 'en'
              ? 'The client receives a lightweight pointer. The full payload is published on a metadata broker.'
              : 'Le client reçoit un pointeur léger. Le payload complet est publié sur un courtier de métadonnées.'}
          </p>
          <ul className="text-xs space-y-1 text-slate-500 list-disc list-inside">
            <li>{language === 'en' ? 'Saves network bandwidth' : 'Économise la bande passante'}</li>
            <li>{language === 'en' ? 'Hides sensitive claims from client' : 'Masque les données sensibles au client'}</li>
            <li>{language === 'en' ? 'Requires broker integration' : 'Nécessite l\'intégration d\'un broker'}</li>
          </ul>
        </div>
      </div>

      {/* The Security Challenge section */}
      <section className="border-t border-slate-100 dark:border-slate-800 pt-6">
        <h2 className="text-2xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'The Security Challenge of Untrusted Brokers' : 'Le Défi de Sécurité des Brokers non Securisés'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 leading-relaxed mb-4">
          {language === 'en'
            ? 'In a distributed environment, the metadata broker (e.g., Kafka, shared RocksDB instances, caching layers) is often treated as an untrusted channel. If a classic INDIRECT token is published, the full JWT is exposed in cleartext. Anyone with read access to the broker can bypass client-side protection and read sensitive payload properties.'
            : 'Dans un environnement distribué, le broker de métadonnées (ex: Kafka, instances RocksDB partagées) est souvent considéré comme non sécurisé (untrusted). Si un jeton INDIRECT classique est publié, le JWT complet y transite en clair. N\'importe quel acteur ayant un accès en lecture au broker peut contourner la protection côté client et lire des données sensibles.'}
        </p>

        <Admonition type="security" title={language === 'en' ? 'The Solution: SECURE_PAYLOAD (Protocol V4 §12)' : 'La Solution : SECURE_PAYLOAD (Protocole V4 §12)'}>
          {language === 'en'
            ? 'To guarantee strict confidentiality, Veridot introduces the SECURE_PAYLOAD entry type (Tag 0x07). This protocol layer utilizes high-performance hybrid encryption to protect sensitive token payloads before publishing them, ensuring that only authorized verifying processors can extract and read the data.'
            : 'Pour garantir une confidentialité absolue, Veridot introduit le type d\'entrée SECURE_PAYLOAD (Tag 0x07). Ce protocole applique un chiffrement hybride à haute performance pour protéger les charges utiles sensibles avant publication, s\'assurant que seuls les processeurs de vérification autorisés puissent extraire et déchiffrer les données.'}
        </Admonition>
      </section>

      {/* Sequence Diagrams Comparison */}
      <section className="border-t border-slate-100 dark:border-slate-800 pt-6">
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Sequence Diagrams Comparison' : 'Comparatif des Diagrammes de Séquence'}
        </h2>
        
        <div className="space-y-6">
          <div>
            <h4 className="font-semibold text-sm text-red-600 dark:text-red-400 mb-2">
              {language === 'en' ? '1. Classic Indirect Mode Flow (Security Risk)' : '1. Flux en Mode Indirect Classique (Risque de Sécurité)'}
            </h4>
            <Mermaid 
              chart={CLASSIC_FLOW_CHART} 
              caption={language === 'en' ? 'Classic indirect mode exposes JWT in cleartext on the broker.' : 'Le mode indirect classique expose le JWT en clair sur le broker.'} 
            />
          </div>

          <div>
            <h4 className="font-semibold text-sm text-emerald-600 dark:text-emerald-400 mb-2">
              {language === 'en' ? '2. SECURE_PAYLOAD Mode Flow (End-to-End Encryption)' : '2. Flux en Mode SECURE_PAYLOAD (Chiffrement de Bout en Bout)'}
            </h4>
            <Mermaid 
              chart={SECURE_FLOW_CHART} 
              caption={language === 'en' ? 'SECURE_PAYLOAD encrypts data at source and only authorized verifiers can decrypt.' : 'SECURE_PAYLOAD chiffre les données à la source et seuls les vérificateurs autorisés peuvent déchiffrer.'} 
            />
          </div>
        </div>
      </section>

      {/* Cryptographic Architecture Flowchart */}
      <section className="border-t border-slate-100 dark:border-slate-800 pt-6">
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Cryptographic Flow & Architecture' : 'Flux et Architecture Cryptographique'}
        </h2>

        <div className="bg-slate-900 dark:bg-black rounded-lg p-5 text-emerald-400 font-mono text-xs overflow-x-auto leading-relaxed border border-slate-800">
          {language === 'en' ? (
            <pre>{`[Data Source] ───────────► (AES-256-GCM) ───────────► [Ciphertext (Tag 0x04)]
                                ▲
                                │ (Encrypts with key_sym)
                        [Symmetric Key (key_sym)]
                                │
          ┌─────────────────────┴─────────────────────┐
          ▼ (RSA Encapsulation)                       ▼ (ECDH Key Agreement)
     [Recipient A]                               [Recipient B]
    Encrypt key_sym                             Generate ephemeral EC key
    with Public RSA                             Compute Shared Secret -> HKDF
          │                                           │
          ▼                                           ▼
  [RecipientBlock A]                          [RecipientBlock B]
  (Recipient ID + Encrypted Key)              (Recipient ID + Encrypted Key + Ephemeral Public Key)
          │                                           │
          └─────────────────────┬─────────────────────┘
                                ▼
                   [SECURE_PAYLOAD Payload (TLV)]
                                │
                    (Signed with Long-Term Key)
                                │
                                ▼
                       [Envelope on Broker]`}</pre>
          ) : (
            <pre>{`[Données Source] ─────────► (AES-256-GCM) ───────────► [Texte Chiffré (Tag 0x04)]
                                ▲
                                │ (Chiffre avec clé_sym)
                         [Clé Symétrique (clé_sym)]
                                │
          ┌─────────────────────┴─────────────────────┐
          ▼ (Encapsulation RSA)                       ▼ (Accord de Clé ECDH)
    [Destinataire A]                            [Destinataire B]
    Chiffrer clé_sym                            Générer clé EC éphémère
    avec RSA Public                             Calculer Secret Partagé -> HKDF
          │                                           │
          ▼                                           ▼
   [BlocDestinataire A]                        [BlocDestinataire B]
  (ID + Clé Chiffrée)                         (ID + Clé Chiffrée + Clé Publique Éphémère)
          │                                           │
          └─────────────────────┬─────────────────────┘
                                ▼
                  [Payload SECURE_PAYLOAD (TLV)]
                                │
                    (Signé avec clé Long-Terme)
                                │
                                ▼
                       [Enveloppe sur Broker]`}</pre>
          )}
        </div>
      </section>

      {/* Modes of Operation */}
      <section className="border-t border-slate-100 dark:border-slate-800 pt-6 space-y-4">
        <h2 className="text-xl font-bold text-slate-900 dark:text-white">
          {language === 'en' ? 'Operational Modes of SECURE_PAYLOAD' : 'Modes Opérationnels de SECURE_PAYLOAD'}
        </h2>
        <div className="space-y-4 text-sm text-slate-600 dark:text-slate-400">
          <div className="border border-slate-100 dark:border-slate-800 rounded-lg p-4">
            <h4 className="font-bold text-slate-900 dark:text-white mb-1">
              {language === 'en' ? '1. Broadcast Public Mode' : '1. Mode Broadcast Public'}
            </h4>
            <p>
              {language === 'en'
                ? 'Used when payload data does not require confidentiality but needs strong integrity and binding to the scope. No recipients list is supplied; the payload is stored in plaintext on the broker inside a signed, tamper-proof envelope. Any processor on the scope can read it.'
                : 'Utilisé lorsque les données ne requièrent pas de confidentialité mais exigent une intégrité forte et une liaison stricte au scope. Aucun destinataire n\'est spécifié; le payload est stocké en clair sur le broker dans une enveloppe signée inviolable. Tout processeur du scope peut le lire.'}
            </p>
          </div>

          <div className="border border-slate-100 dark:border-slate-800 rounded-lg p-4">
            <h4 className="font-bold text-slate-900 dark:text-white mb-1">
              {language === 'en' ? '2. Unicast Mode' : '2. Mode Unicast'}
            </h4>
            <p>
              {language === 'en'
                ? 'Confidential payload targeted at a single verifying processor. The ephemeral symmetric key is encrypted using the recipient processor\'s public key (either RSA or EC-ECDH).'
                : 'Charge utile confidentielle ciblée vers un unique processeur de vérification. La clé symétrique éphémère est chiffrée avec la clé publique du destinataire (soit RSA, soit EC-ECDH).'}
            </p>
          </div>

          <div className="border border-slate-100 dark:border-slate-800 rounded-lg p-4">
            <h4 className="font-bold text-slate-900 dark:text-white mb-1">
              {language === 'en' ? '3. Multicast Mode' : '3. Mode Multicast'}
            </h4>
            <p>
              {language === 'en'
                ? 'Confidential payload targeted at a closed group of verifying processors. Multiple recipient blocks are created in the SECURE_PAYLOAD entry, allowing each authorized processor to decrypt the same symmetric key using its own private key.'
                : 'Charge utile confidentielle destinée à un groupe fermé de processeurs. Plusieurs blocs de destinataires sont inclus dans l\'entrée SECURE_PAYLOAD, permettant à chaque processeur autorisé de déchiffrer la même clé symétrique avec sa propre clé privée.'}
            </p>
          </div>
        </div>
      </section>

      {/* Code Examples */}
      <section className="border-t border-slate-100 dark:border-slate-800 pt-6 space-y-6">
        <h2 className="text-xl font-bold text-slate-900 dark:text-white">
          {language === 'en' ? 'API Code Examples' : 'Exemples de Code API'}
        </h2>

        <div>
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-300 mb-2">
            {language === 'en' ? 'Classic Direct Mode' : 'Mode Direct Classique'}
          </h3>
          <CodeBlock code={DIRECT_CODE} language="java" title="Direct Mode" />
        </div>

        <div>
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-300 mb-2">
            {language === 'en' ? 'Classic Indirect Mode (Unencrypted)' : 'Mode Indirect Classique (Non Chiffré)'}
          </h3>
          <CodeBlock code={INDIRECT_CODE} language="java" title="Indirect Mode" />
        </div>

        <div>
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-300 mb-2">
            {language === 'en' ? 'SECURE_PAYLOAD: Confidential (Unicast/Multicast)' : 'SECURE_PAYLOAD : Confidentiel (Unicast/Multicast)'}
          </h3>
          <CodeBlock code={SECURE_PAYLOAD_UNICAST_CODE} language="java" title="Confidential SECURE_PAYLOAD" />
        </div>

        <div>
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-300 mb-2">
            {language === 'en' ? 'SECURE_PAYLOAD: Broadcast Public' : 'SECURE_PAYLOAD : Broadcast Public'}
          </h3>
          <CodeBlock code={SECURE_PAYLOAD_BROADCAST_CODE} language="java" title="Broadcast SECURE_PAYLOAD" />
        </div>
      </section>
    </div>
  );
}
