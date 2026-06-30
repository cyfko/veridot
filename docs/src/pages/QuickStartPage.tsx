import { useApp } from '../context/AppContext';
import { CodeBlock } from '../components/CodeBlock';
import { Admonition } from '../components/Admonition';
import { Mermaid } from '../components/Mermaid';

const STEP1_CODE = `import java.security.*;
import java.security.spec.*;
import java.nio.file.*;
import java.util.Properties;
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.impl.*;
import io.github.cyfko.veridot.kafka.KafkaBroker;

// ── Step 1: Configure the Kafka Broker ──────────────────────────
Properties kafkaProps = new Properties();
kafkaProps.setProperty("bootstrap.servers", "kafka:9092");
kafkaProps.setProperty("embedded.db.path", "/var/lib/veridot");
Broker broker = KafkaBroker.of(kafkaProps);`;

const STEP2_CODE = `// ── Step 2: Load your long-term private key ────────────────────
// In production: use Vault, Kubernetes Secret, or KMS
byte[] pkcs8Bytes = Files.readAllBytes(Paths.get("/etc/veridot/private.key"));
PrivateKey longTermKey = KeyFactory.getInstance("RSA")
    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8Bytes));`;

const STEP3_CODE = `// ── Step 3: Configure TrustRoot ────────────────────────────────
// The TrustRoot resolves issuer IDs to long-term public keys.
// Any service that verifies tokens must share the same TrustRoot.
TrustRoot trust = new PublicKeyTrustRoot(signerId -> {
    byte[] pem = Files.readAllBytes(
        Paths.get("/etc/veridot/trust/" + signerId + ".pub.pem"));
    return parsePemPublicKey(pem); // your PEM parsing helper
});`;

const STEP4_CODE = `// ── Step 4: Build the signer/verifier ──────────────────────────
// GenericSignerVerifier implements DataSigner, TokenVerifier,
// TokenRevoker and TokenTracker — all four in one class.
var sv = new GenericSignerVerifier(
    broker,
    trust,
    "auth-service",      // your unique issuer ID
    longTermKey,
    Algorithm.ED25519    // recommended: Ed25519 (NIST SP 800-186)
);`;

const STEP5_CODE = `// ── Step 5: Sign a payload ──────────────────────────────────────
// DIRECT mode: the JWT token is returned directly to the caller.
String token = sv.sign("alice@example.com",
    BasicConfigurer.builder()
        .groupId("user-alice")       // logical group (e.g. userId)
        .sequenceId("mobile-app-v2") // session ID (UUID if omitted)
        .validity(3600)              // TTL: 1 hour
        .build());

// token = "eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiI0OiJ9..."
// → send to client as: Authorization: Bearer <token>`;

const STEP6_CODE = `// ── Step 6: Verify (from ANY service sharing the same broker) ──
// This reads from the local RocksDB cache — no Kafka call needed.
// Verification time: sub-millisecond.
VerifiedData<String> result = sv.verify(token, s -> s);

System.out.println(result.data());       // "alice@example.com"
System.out.println(result.groupId());    // "user-alice"
System.out.println(result.sequenceId()); // "mobile-app-v2"`;

const STEP7_CODE = `// ── Step 7: Revoke ──────────────────────────────────────────────
// Revoke one specific session:
sv.revoke("user-alice", "mobile-app-v2");

// Revoke ALL sessions for this group (e.g., user logout from all devices):
sv.revoke("user-alice", null);

// Any subsequent verify() call will throw BrokerExtractionException.`;

const FLOW_DIAGRAM = `sequenceDiagram
    participant Client as "Client"
    participant ServiceA as "Service A (Signer)"
    participant Broker as "Broker (Kafka/SQL)"
    participant Cache as "Local Cache (RocksDB)"
    participant ServiceB as "Service B (Verifier)"

    ServiceA->>ServiceA: "Generate ephemeral key pair"
    ServiceA->>ServiceA: "Sign payload (JWT)"
    ServiceA->>Broker: "Publish KEY_EPOCH envelope"
    ServiceA->>Broker: "Publish LIVENESS=ACTIVE envelope"
    Broker-->>Cache: "Propagate to all consumers"
    ServiceA-->>Client: "Return JWT token"

    Client->>ServiceB: "Request with JWT token"
    ServiceB->>Cache: "Read KEY_EPOCH (local, sub-ms)"
    ServiceB->>Cache: "Check LIVENESS=ACTIVE"
    ServiceB->>ServiceB: "Verify JWT signature"
    ServiceB-->>Client: "Authorized response"

    ServiceA->>Broker: "Publish LIVENESS=REVOKED"
    Broker-->>Cache: "Propagate revocation"
    Client->>ServiceB: "Request with (now revoked) JWT"
    ServiceB->>Cache: "Check LIVENESS (REVOKED)"
    ServiceB-->>Client: "401 Unauthorized"`;

export function QuickStartPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">
          {language === 'en' ? 'Getting Started' : 'Démarrage'}
        </p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Quick Start' : 'Démarrage rapide'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'From zero to a working sign/verify/revoke loop in 5 minutes. This guide uses the Kafka broker — the recommended choice for distributed environments.'
            : 'De zéro à un cycle sign/verify/revoke fonctionnel en 5 minutes. Ce guide utilise le broker Kafka — le choix recommandé pour les environnements distribués.'}
        </p>
      </div>

      <Admonition type="warning" title={language === 'en' ? 'Not for Production' : 'Pas pour la production'}>
        {language === 'en'
          ? 'The examples below load keys from local files for simplicity. In production, load private keys and trust roots from a KMS, Vault, or HSM. See the Security guide for production patterns.'
          : 'Les exemples ci-dessous chargent les clés depuis des fichiers locaux pour simplifier. En production, chargez les clés privées et TrustRoot depuis un KMS, Vault ou HSM. Consultez le guide Sécurité pour les patterns de production.'}
      </Admonition>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-2">
          {language === 'en' ? 'How it works' : 'Comment ça fonctionne'}
        </h2>
        <Mermaid chart={FLOW_DIAGRAM} caption={language === 'en' ? 'Sign → Distribute → Verify → Revoke flow' : 'Flux Sign → Distribuer → Vérifier → Révoquer'} />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Step-by-step guide' : 'Guide étape par étape'}
        </h2>

        <div className="space-y-6">
          <StepCard
            number={1}
            title={language === 'en' ? 'Configure the broker' : 'Configurer le broker'}
            description={language === 'en'
              ? 'The broker transports protocol entries between service instances. It holds no cryptographic authority.'
              : 'Le broker transporte les entrées de protocole entre les instances de service. Il ne détient aucune autorité cryptographique.'}
          >
            <CodeBlock code={STEP1_CODE} language="java" />
          </StepCard>

          <StepCard
            number={2}
            title={language === 'en' ? 'Load your long-term private key' : 'Charger votre clé privée long terme'}
            description={language === 'en'
              ? 'This key signs KEY_EPOCH envelopes, making them verifiable by any service sharing the corresponding public TrustRoot.'
              : 'Cette clé signe les enveloppes KEY_EPOCH, les rendant vérifiables par tout service partageant la TrustRoot publique correspondante.'}
          >
            <CodeBlock code={STEP2_CODE} language="java" />
          </StepCard>

          <StepCard
            number={3}
            title={language === 'en' ? 'Configure the TrustRoot' : 'Configurer la TrustRoot'}
            description={language === 'en'
              ? 'The TrustRoot is the sole source of cryptographic trust. It resolves issuer IDs to long-term public keys, independently of the broker.'
              : 'La TrustRoot est l\'unique source de confiance cryptographique. Elle résout les IDs d\'émetteur en clés publiques long terme, indépendamment du broker.'}
          >
            <CodeBlock code={STEP3_CODE} language="java" />
          </StepCard>

          <StepCard
            number={4}
            title={language === 'en' ? 'Build GenericSignerVerifier' : 'Construire GenericSignerVerifier'}
            description={language === 'en'
              ? 'GenericSignerVerifier is the single entry point. It implements DataSigner, TokenVerifier, TokenRevoker, and TokenTracker.'
              : 'GenericSignerVerifier est le point d\'entrée unique. Il implémente DataSigner, TokenVerifier, TokenRevoker et TokenTracker.'}
          >
            <CodeBlock code={STEP4_CODE} language="java" />
          </StepCard>

          <StepCard
            number={5}
            title={language === 'en' ? 'Sign a payload' : 'Signer un payload'}
            description={language === 'en'
              ? 'sign() serializes the payload, creates an ephemeral key pair, builds a signed JWT, and publishes KEY_EPOCH + LIVENESS=ACTIVE to the broker — all atomically.'
              : 'sign() sérialise le payload, crée une paire de clés éphémère, construit un JWT signé, et publie KEY_EPOCH + LIVENESS=ACTIVE sur le broker — tout atomiquement.'}
          >
            <CodeBlock code={STEP5_CODE} language="java" />
          </StepCard>

          <StepCard
            number={6}
            title={language === 'en' ? 'Verify the token' : 'Vérifier le token'}
            description={language === 'en'
              ? 'verify() runs a 10-step pipeline: structural validation, TrustRoot resolution, capability check, monotonic version check, liveness check, and JWT signature verification — all in under 1ms from the local cache.'
              : 'verify() exécute un pipeline en 10 étapes : validation structurelle, résolution TrustRoot, vérification de capacité, vérification de version monotone, vérification de liveness et vérification de signature JWT — tout en moins d\'1ms depuis le cache local.'}
          >
            <CodeBlock code={STEP6_CODE} language="java" />
          </StepCard>

          <StepCard
            number={7}
            title={language === 'en' ? 'Revoke sessions' : 'Révoquer les sessions'}
            description={language === 'en'
              ? 'revoke() publishes a signed LIVENESS=REVOKED entry. All verifiers reject the corresponding tokens as soon as they observe the entry through the broker.'
              : 'revoke() publie une entrée LIVENESS=REVOKED signée. Tous les vérificateurs rejettent les tokens correspondants dès qu\'ils observent l\'entrée via le broker.'}
          >
            <CodeBlock code={STEP7_CODE} language="java" />
          </StepCard>
        </div>
      </section>

      <Admonition type="tip" title={language === 'en' ? 'Next steps' : 'Prochaines étapes'}>
        <ul className="list-disc pl-4 space-y-1 mt-1">
          <li>{language === 'en' ? 'Integrate with Spring Boot → ' : 'Intégrer avec Spring Boot → '}<a className="text-violet-600 dark:text-violet-400 hover:underline" href="/guides/spring-boot">{language === 'en' ? 'Spring Boot Guide' : 'Guide Spring Boot'}</a></li>
          <li>{language === 'en' ? 'Use INDIRECT mode for large payloads → ' : 'Utiliser le mode INDIRECT pour les gros payloads → '}<a className="text-violet-600 dark:text-violet-400 hover:underline" href="/guides/distribution-modes">{language === 'en' ? 'Distribution Modes' : 'Modes de distribution'}</a></li>
          <li>{language === 'en' ? 'Enforce session limits → ' : 'Appliquer des limites de session → '}<a className="text-violet-600 dark:text-violet-400 hover:underline" href="/guides/session-capacity">{language === 'en' ? 'Session Capacity' : 'Capacité de session'}</a></li>
          <li>{language === 'en' ? 'Configure TrustRoot for production → ' : 'Configurer TrustRoot pour la production → '}<a className="text-violet-600 dark:text-violet-400 hover:underline" href="/security/trust-root">{language === 'en' ? 'TrustRoot in Production' : 'TrustRoot en production'}</a></li>
        </ul>
      </Admonition>
    </div>
  );
}

function StepCard({ number, title, description, children }: {
  number: number;
  title: string;
  description: string;
  children: React.ReactNode;
}) {
  return (
    <div className="border border-slate-200 dark:border-slate-700 rounded-xl overflow-hidden">
      <div className="px-5 py-4 bg-slate-50 dark:bg-slate-800/50 border-b border-slate-200 dark:border-slate-700 flex items-start gap-3">
        <span className="flex-shrink-0 h-7 w-7 rounded-full bg-violet-600 text-white text-sm font-bold flex items-center justify-center">
          {number}
        </span>
        <div>
          <h3 className="font-semibold text-slate-900 dark:text-white">{title}</h3>
          <p className="text-sm text-slate-600 dark:text-slate-400 mt-0.5">{description}</p>
        </div>
      </div>
      <div className="p-4 bg-white dark:bg-slate-900">
        {children}
      </div>
    </div>
  );
}
