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

const PRIVATE_UNICAST_CODE = `// PRIVATE mode: Confidential Unicast / Multicast mode (Encrypted payload)
List<String> authorizedRecipients = List.of("processor-alice", "processor-bob");
byte[] sensitiveData = "Confidential customer details".getBytes(StandardCharsets.UTF_8);

String secureToken = signer.sign(sensitiveData,
    BasicConfigurer.builder()
        .groupId("confidential-docs")
        .sequenceId("doc-123")
        .distribution(DistributionMode.PRIVATE)
        .validity(3600)
        .recipients(authorizedRecipients)
        .mimeType("application/json")
        .build()
);

// Decrypting at recipient side:
VerifiedData<byte[]> verified = verifier.verify(secureToken, bytes -> bytes);
byte[] clearData = verified.data();`;

const PRIVATE_BROADCAST_CODE = `// PRIVATE mode: Broadcast Public mode (Plaintext payload on broker, signed envelope)
byte[] publicData = "Public announcement".getBytes(StandardCharsets.UTF_8);

String broadcastToken = signer.sign(publicData,
    BasicConfigurer.builder()
        .groupId("public-news")
        .sequenceId("announcement-45")
        .distribution(DistributionMode.PRIVATE)
        .validity(3600)
        .mimeType("text/plain")
        // null/empty recipients list implies Broadcast Public mode
        .build()
);

// Verifying at recipient side:
VerifiedData<byte[]> verified = verifier.verify(broadcastToken, bytes -> bytes);
byte[] clearData = verified.data();`;

const DIRECT_FLOW_CHART = `sequenceDiagram
    autonumber
    actor Client
    participant Emitter as Emitter Node
    participant Broker as Shared Broker
    participant Verifier as Verifier Node

    Note over Emitter, Verifier: Direct Session Token Flow (By-Value)
    Emitter->>Broker: Publish verification metadata (KEY_EPOCH)
    Emitter->>Client: Return full signed JWT token
    Client->>Verifier: Present full signed JWT token
    Verifier->>Broker: Fetch verification metadata if not cached
    Broker-->>Verifier: Return KEY_EPOCH metadata
    Verifier->>Verifier: Validate JWT signature & authenticate Client`;

const INDIRECT_FLOW_CHART = `sequenceDiagram
    autonumber
    actor Client
    participant Emitter as Emitter Node
    participant Broker as Shared Broker
    participant Verifier as Verifier Node

    Note over Emitter, Verifier: Indirect Session Token Flow (By-Reference)
    Emitter->>Broker: Publish full JWT session metadata
    Emitter->>Client: Send lightweight Token (messageId reference)
    Client->>Verifier: Present messageId reference
    Verifier->>Broker: Fetch full JWT metadata using messageId
    Broker-->>Verifier: Return full JWT
    Verifier->>Verifier: Validate signature & authenticate Client`;

const PRIVATE_FLOW_CHART = `sequenceDiagram
    autonumber
    actor Client
    participant Emitter as Emitter Node
    participant Broker as Untrusted Broker
    participant Verifier as Verifier Node
    participant Attacker as Passive Listener

    Note over Emitter, Verifier: Private Business Payload Flow (Encrypted)
    Emitter->>Emitter: Generate ephemeral key_sym & encrypt payload (AES-GCM)
    Emitter->>Emitter: Encapsulate key_sym with Verifier's public key (RSA/ECDH)
    Emitter->>Broker: Publish signed SECURE_PAYLOAD envelope
    Note over Broker: Data is encrypted, key is encapsulated!
    rect rgb(209, 250, 229)
        Attacker->>Broker: Read metadata / logs
        Note over Attacker: Only sees ciphertext & encrypted key blocks (Access Denied)
    end
    Emitter->>Client: Send lightweight Token (PRIVATE reference pointer)
    Client->>Verifier: Present PRIVATE reference pointer
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
          {language === 'en' ? 'Guides · Architecture & Intent' : 'Guides · Architecture & Objectifs'}
        </p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Token Distribution & Secure Payload Modes' : 'Modes de Distribution et Transport Sécurisé'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Understand the different architectural intents of Direct/Indirect session distribution and SECURE_PAYLOAD data transport.'
            : 'Comprendre les différents objectifs d\'architecture entre la distribution de sessions (Direct/Indirect) et le transport sécurisé de données (SECURE_PAYLOAD).'}
        </p>
      </div>

      {/* Overview Cards by Intent */}
      <div className="grid lg:grid-cols-3 gap-6 my-6">
        <div className="border border-slate-200 dark:border-slate-800 rounded-xl p-5 bg-slate-50/50 dark:bg-slate-900/20">
          <h3 className="text-violet-700 dark:text-violet-400 font-bold text-base mb-2">
            {language === 'en' ? 'Direct Mode (DIRECT)' : 'Mode Direct (DIRECT)'}
          </h3>
          <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed mb-3">
            {language === 'en'
              ? 'The token (JWT) is returned directly to the client. Best for lightweight session metadata and roles.'
              : 'Le jeton (JWT) est renvoyé directement au client. Idéal pour les métadonnées de session et rôles légers.'}
          </p>
          <ul className="text-xs space-y-1 text-slate-500 list-disc list-inside">
            <li>{language === 'en' ? 'Zero broker overhead' : 'Zéro dépendance broker'}</li>
            <li>{language === 'en' ? 'Client sees all claims' : 'Le client lit les claims'}</li>
            <li>{language === 'en' ? 'Higher network consumption' : 'Consomme plus de bande passante'}</li>
          </ul>
        </div>

        <div className="border border-slate-200 dark:border-slate-800 rounded-xl p-5 bg-slate-50/50 dark:bg-slate-900/20">
          <h3 className="text-violet-700 dark:text-violet-400 font-bold text-base mb-2">
            {language === 'en' ? 'Indirect Mode (INDIRECT)' : 'Mode Indirect (INDIRECT)'}
          </h3>
          <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed mb-3">
            {language === 'en'
              ? 'Returns a lightweight messageId. The full token is stored on the broker and fetched by the verifier.'
              : 'Retourne un messageId léger. Le token complet est stocké sur le broker et récupéré par le vérificateur.'}
          </p>
          <ul className="text-xs space-y-1 text-slate-500 list-disc list-inside">
            <li>{language === 'en' ? 'Optimizes client bandwidth' : 'Optimise le réseau client'}</li>
            <li>{language === 'en' ? 'Hides claims from clients' : 'Masque les claims au client'}</li>
            <li>{language === 'en' ? 'Ideal for large sessions' : 'Idéal pour sessions volumineuses'}</li>
          </ul>
        </div>

        <div className="border border-slate-200 dark:border-slate-800 rounded-xl p-5 bg-slate-50/50 dark:bg-slate-900/20">
          <h3 className="text-violet-700 dark:text-violet-400 font-bold text-base mb-2">
            {language === 'en' ? 'Private Mode (PRIVATE)' : 'Mode Privé (PRIVATE)'}
          </h3>
          <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed mb-3">
            {language === 'en'
              ? 'Encrypts the payload at the source (AES + RSA/ECDH). Stored on the broker; only authorized receivers can decrypt.'
              : 'Chiffre la charge utile à la source (AES + RSA/ECDH). Stockée sur le broker, seuls les destinataires décryptent.'}
          </p>
          <ul className="text-xs space-y-1 text-slate-500 list-disc list-inside">
            <li>{language === 'en' ? 'End-to-end confidentiality' : 'Confidentialité bout en bout'}</li>
            <li>{language === 'en' ? 'Secures raw business payloads' : 'Sécurise les objets métier'}</li>
            <li>{language === 'en' ? 'Prevents broker-level leaks' : 'Prévient les fuites sur le broker'}</li>
          </ul>
        </div>
      </div>

      {/* Intention and Use Cases */}
      <section className="border-t border-slate-100 dark:border-slate-800 pt-6">
        <h2 className="text-2xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Choosing the Right Mode for the Right Intent' : 'Choisir le Mode Approprié selon l\'Intention'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 leading-relaxed mb-4">
          {language === 'en'
            ? 'It is essential to distinguish between session authorization management and secure data plane transport. While Direct and Indirect modes optimize token size and delivery for client-side consumption, SECURE_PAYLOAD serves as a secure envelope for application data objects. When business data must travel through brokers where intermediate security is untrusted, SECURE_PAYLOAD should be utilized.'
            : 'Il est essentiel de distinguer la gestion des autorisations de session du transport sécurisé des données applicatives. Alors que les modes Direct et Indirect optimisent la taille des jetons pour la consommation client, SECURE_PAYLOAD sert d\'enveloppe sécurisée pour les objets de données applicatives. Lorsque les données métier doivent traverser des brokers dont la sécurité intermédiaire n\'est pas garantie, SECURE_PAYLOAD est la solution appropriée.'}
        </p>

        <Admonition type="info" title={language === 'en' ? 'Design Distinction' : 'Distinction de Conception'}>
          {language === 'en'
            ? 'Standard session metadata (roles, scopes, identifiers) does not require SECURE_PAYLOAD. Use classic Indirect mode for session optimization, and reserve SECURE_PAYLOAD for confidential business objects and sensitive data exchanges.'
            : 'Les métadonnées de session standard (rôles, scopes, identifiants) ne requièrent pas SECURE_PAYLOAD. Utilisez le mode Indirect classique pour l\'optimisation de session, et réservez SECURE_PAYLOAD pour les objets métier confidentiels et les échanges de données sensibles.'}
        </Admonition>
      </section>

      {/* Flowchart Comparisons */}
      <section className="border-t border-slate-100 dark:border-slate-800 pt-6">
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Operational & Architectural Flows' : 'Flux Opérationnels et Architecturaux'}
        </h2>
        
        <div className="space-y-6">
          <div>
            <h4 className="font-semibold text-sm text-violet-600 dark:text-violet-400 mb-2">
              {language === 'en' ? '1. Session Metadata: Direct Mode (By-Value)' : '1. Métadonnées de Session : Mode Direct (Par Valeur)'}
            </h4>
            <Mermaid 
              chart={DIRECT_FLOW_CHART} 
              caption={language === 'en' ? 'Direct mode returns the full self-contained JWT token directly to the client.' : 'Le mode direct renvoie le token JWT complet autonome directement au client.'} 
            />
          </div>

          <div>
            <h4 className="font-semibold text-sm text-violet-600 dark:text-violet-400 mb-2">
              {language === 'en' ? '2. Session Metadata: Indirect Mode (Bandwidth Optimization)' : '2. Métadonnées de Session : Mode Indirect (Optimisation)'}
            </h4>
            <Mermaid 
              chart={INDIRECT_FLOW_CHART} 
              caption={language === 'en' ? 'Indirect mode optimizes token transmission to the client via pointer references.' : 'Le mode indirect optimise la transmission de jeton au client via des références de pointeurs.'} 
            />
          </div>

          <div>
            <h4 className="font-semibold text-sm text-emerald-600 dark:text-emerald-400 mb-2">
              {language === 'en' ? '3. Secure Data Transport: Private Mode (End-to-End Encryption)' : '3. Transport Sécurisé : Mode Privé (Chiffrement bout en bout)'}
            </h4>
            <Mermaid 
              chart={PRIVATE_FLOW_CHART} 
              caption={language === 'en' ? 'Private mode encrypts actual business payloads at source.' : 'Le mode privé chiffre les charges utiles métier réelles directement à la source.'} 
            />
          </div>
        </div>
      </section>

      {/* Cryptographic Architecture Flowchart */}
      <section className="border-t border-slate-100 dark:border-slate-800 pt-6">
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Cryptographic Key Encapsulation (SECURE_PAYLOAD)' : 'Encapsulation de Clé Cryptographique (SECURE_PAYLOAD)'}
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
            {language === 'en' ? 'Private Mode: Confidential (Unicast/Multicast)' : 'Mode Private : Confidentiel (Unicast/Multicast)'}
          </h3>
          <CodeBlock code={PRIVATE_UNICAST_CODE} language="java" title="Confidential Private Mode" />
        </div>

        <div>
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-300 mb-2">
            {language === 'en' ? 'Private Mode: Broadcast Public' : 'Mode Private : Broadcast Public'}
          </h3>
          <CodeBlock code={PRIVATE_BROADCAST_CODE} language="java" title="Broadcast Private Mode" />
        </div>
      </section>
    </div>
  );
}
