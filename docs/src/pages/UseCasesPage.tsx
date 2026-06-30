import { useApp } from '../context/AppContext';
import { CodeBlock } from '../components/CodeBlock';
import { Admonition } from '../components/Admonition';
import { Mermaid } from '../components/Mermaid';
import { Shield, RefreshCw, Key, Share2, Layers, BarChart3, Database, EyeOff } from 'lucide-react';

const M2M_CODE = `// ── Service A (Signer) ──────────────────────────────────────────
// Service A generates its credential to call Service B.
// We configure a short-lived 15-minute M2M token.
String m2mToken = signer.sign("service-a-identity",
    BasicConfigurer.builder()
        .groupId("m2m-clients")
        .sequenceId("service-a")
        .validity(900) // 15 minutes
        .build());

// ── Service B (Verifier) ────────────────────────────────────────
// Service B validates Service A's identity locally in <1ms.
VerifiedData<String> client = verifier.verify(m2mToken, s -> s);
if ("service-a-identity".equals(client.data())) {
    // Grant access to billing resources...
}`;

const ONETIME_CODE = `// ── Step 1: Issue the one-time link (Signer) ─────────────────────
// We bind the download action to a unique sequenceId (e.g., ticket UUID)
String ticketId = UUID.randomUUID().toString();
String downloadToken = signer.sign("file-id-45920",
    BasicConfigurer.builder()
        .groupId("downloads")
        .sequenceId(ticketId)
        .validity(300) // valid for 5 minutes
        .build());

// Return link: https://api.yoursite.com/download?ticket=downloadToken

// ── Step 2: Redeem the link (Verifier) ───────────────────────────
// Upon client request, verify the token and instantly revoke it:
VerifiedData<String> verified = verifier.verify(downloadToken, s -> s);
String fileId = verified.data();

// Revoke immediately to prevent any re-use (replay)
revoker.revoke("downloads", verified.sequenceId());

// Serve the file payload safely...`;

const DELEGATION_CODE = `// Parent service delegates temporary sub-permissions
String delegationToken = signer.sign("read-only:folder-123",
    BasicConfigurer.builder()
        .groupId("delegated-access")
        .sequenceId("temp-session-" + UUID.randomUUID())
        .validity(1800) // 30 minutes
        .build());`;

const RATELIMIT_CODE = `// ── Edge Gateway (Verifier) ──────────────────────────────────────
// The edge API gateway intercepts requests. It pulls dynamic rate-limiting
// configurations from its local RocksDB cache and verifies token validity.
VerifiedData<String> client = verifier.verify(apiToken, s -> s);
String tenantId = client.groupId();

// 1. Fetch dynamic config mapped to this tenant
Optional<ConfigEntry> limitConfig = configResolver.resolveConfig("group:" + tenantId);
if (limitConfig.isPresent()) {
    int maxReqs = limitConfig.get().maxSessions(); // used as rate-limit threshold
    // Enforce rate limit locally using a token bucket in memory...
}`;

const STORAGE_CODE = `// ── Step 1: Issue Temporary Storage Ticket (Backend) ─────────────
// Grant download access to a single file inside tenant-102 bucket
String folderTicket = signer.sign("folder-id-abc/file-xyz.pdf",
    BasicConfigurer.builder()
        .groupId("tenant-102-files")
        .sequenceId("ticket-" + UUID.randomUUID())
        .validity(300) // 5 minutes expiration
        .build());

// ── Step 2: Verify at File Server Node (Storage Proxy) ───────────
// The client connects directly to the static file server with the ticket.
// The file server validates the signature and paths locally.
VerifiedData<String> ticket = verifier.verify(folderTicket, s -> s);
String filePath = ticket.data();
serveLocalFile(filePath);`;

const CONSENT_CODE = `// ── Step 1: User Withdraws Consent (Issuer) ─────────────────────
// User revokes data processing rights. We publish a revoked state for this scope.
revoker.revoke("gdpr-consents-user-456", "analytics-sharing");

// ── Step 2: Validate prior to processing (Processor Node) ────────
// Before running an analytics job, check if the consent is still active
boolean hasConsent = tracker.hasActiveToken("gdpr-consents-user-456");
if (!hasConsent) {
    // Skip processing user data...
}`;

const FLOW_M2M = `sequenceDiagram
    participant A as "Service A"
    participant B as "Service B"
    participant Broker as "Broker"
    participant Cache as "Cache Local (RocksDB)"

    A->>Broker: "KEY_EPOCH (Signature A)"
    Broker-->>Cache: "Synchronisation"
    A->>B: "Requête API + Token M2M (Ed25519)"
    B->>Cache: "Résolution clé A (sous-1ms)"
    B->>B: "Validation signature asymétrique"
    B-->>A: "Réponse 200 OK"`;

export function UseCasesPage() {
  const { language } = useApp();

  return (
    <div className="space-y-10">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">
          {language === 'en' ? 'Getting Started' : 'Démarrage'}
        </p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Use Cases & Design Patterns' : 'Cas d\'Usage & Design Patterns'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'While Veridot excels at user authentication, it is fundamentally a distributed state attestation engine. Discover real-world problems solved by Veridot beyond simple web tokens.'
            : 'Bien que Veridot excelle dans l\'authentification, il s\'agit fondamentalement d\'un moteur distribué d\'attestation d\'état. Découvrez les problèmes réels résolus par la librairie.'}
        </p>
      </div>

      {/* Grid of Use cases */}
      <div className="grid sm:grid-cols-2 gap-4">
        {[
          {
            icon: Shield,
            title: language === 'en' ? 'Machine-to-Machine Auth' : 'Authentification M2M / Inter-services',
            desc: language === 'en' ? 'Secure microservice communications without central OAuth2/IdP bottlenecks or database API key checks.' : 'Sécurisez les appels inter-services sans le goulot d\'étranglement d\'un serveur OAuth2 centralisé.'
          },
          {
            icon: RefreshCw,
            title: language === 'en' ? 'One-Time Action Links' : 'Tokens à Usage Unique (Actions)',
            desc: language === 'en' ? 'Create tamper-proof download links or password reset codes that invalidate instantly upon use.' : 'Générez des liens de téléchargement ou de réinitialisation qui s\'invalident dès le premier clic.'
          },
          {
            icon: Key,
            title: language === 'en' ? 'Temporary Delegation' : 'Délégation Temporaire de Droits',
            desc: language === 'en' ? 'Delegate scoped, time-bound read/write privileges to downstream workers without database mutations.' : 'Attribuez des droits temporaires à des sous-processus sans avoir à modifier vos tables de profils.'
          },
          {
            icon: Share2,
            title: language === 'en' ? 'Cross-Tenant Access' : 'Partage de Ressources Inter-Entreprises',
            desc: language === 'en' ? 'Secure cross-organization data exchange by sharing long-term public keys in a decentralized trust root.' : 'Échangez des données de façon sécurisée entre deux clients distincts via échange de clés publiques.'
          }
        ].map((item, i) => {
          const Icon = item.icon;
          return (
            <div key={i} className="border border-slate-200 dark:border-slate-700 rounded-xl p-5 bg-slate-50 dark:bg-slate-900/30 flex gap-4">
              <div className="h-10 w-10 rounded-lg bg-violet-100 dark:bg-violet-900/30 text-violet-700 dark:text-violet-300 flex items-center justify-center flex-shrink-0">
                <Icon size={20} />
              </div>
              <div>
                <h3 className="font-semibold text-slate-900 dark:text-white mb-1">{item.title}</h3>
                <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">{item.desc}</p>
              </div>
            </div>
          );
        })}
      </div>

      <hr className="border-slate-200 dark:border-slate-800" />

      {/* Pattern 1: M2M */}
      <section className="space-y-4">
        <div className="flex items-center gap-2">
          <Layers className="text-violet-600 dark:text-violet-400" size={20} />
          <h2 className="text-xl font-bold text-slate-900 dark:text-white">
            {language === 'en' ? 'Pattern 1: High-Performance M2M Authorization' : 'Pattern 1 : Autorisation M2M Ultra-Rapide'}
          </h2>
        </div>
        <p className="text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'In microservices, calling a central identity provider (IdP) for service-to-service calls introduces high latency. With Veridot, services sign assertions using ephemeral key pairs. The receiving service verifies the signature locally against a TrustRoot in under 1ms.'
            : 'Dans les architectures microservices, appeler un serveur d\'autorisation centralisé pour valider les requêtes inter-services détériore les performances. Avec Veridot, la validation s\'effectue localement sans aucun appel réseau.'}
        </p>
        <Mermaid chart={FLOW_M2M} caption={language === 'en' ? 'M2M Signature & Local Verification' : 'Signature M2M & Vérification Locale'} />
        <CodeBlock code={M2M_CODE} language="java" title="M2M implementation" />
      </section>

      {/* Pattern 2: One-time tickets */}
      <section className="space-y-4">
        <div className="flex items-center gap-2">
          <RefreshCw className="text-violet-600 dark:text-violet-400" size={20} />
          <h2 className="text-xl font-bold text-slate-900 dark:text-white">
            {language === 'en' ? 'Pattern 2: One-Time Action Tokens (Anti-Replay)' : 'Pattern 2 : Liens d\'Action à Usage Unique'}
          </h2>
        </div>
        <p className="text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'For actions like database report generation, password resets, or secure file downloads, tokens must only work once. Veridot enforces this by executing an instant revocation upon first validation.'
            : 'Pour des actions comme la génération de rapports, la réinitialisation de comptes ou le téléchargement sécurisé, les tokens doivent expirer après la première utilisation.'}
        </p>
        <CodeBlock code={ONETIME_CODE} language="java" title="One-time token lifecycle" />
      </section>

      {/* Pattern 3: Scoped Delegation */}
      <section className="space-y-4">
        <div className="flex items-center gap-2">
          <Key className="text-violet-600 dark:text-violet-400" size={20} />
          <h2 className="text-xl font-bold text-slate-900 dark:text-white">
            {language === 'en' ? 'Pattern 3: Scoped Capability Delegation' : 'Pattern 3 : Délégation de Privilèges Ciblée'}
          </h2>
        </div>
        <p className="text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'When parent workflows trigger async background workers, they can delegate a restricted sub-scope (e.g. read access to a specific folder). The worker carries this delegation token, verifiable locally by storage controllers.'
            : 'Lorsqu\'un service parent lance une tâche asynchrone, il peut lui déléguer des privilèges restreints (ex. lecture d\'un dossier). Le worker s\'authentifie grâce à ce token auprès des contrôleurs de stockage.'}
        </p>
        <CodeBlock code={DELEGATION_CODE} language="java" title="Capability delegation" />
      </section>

      {/* Pattern 4: API Rate Limiting */}
      <section className="space-y-4">
        <div className="flex items-center gap-2">
          <BarChart3 className="text-violet-600 dark:text-violet-400" size={20} />
          <h2 className="text-xl font-bold text-slate-900 dark:text-white">
            {language === 'en' ? 'Pattern 4: Distributed API Gateways & Edge Rate Limiting' : 'Pattern 4 : API Gateways Distribuées & Limitation de Débit'}
          </h2>
        </div>
        <p className="text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'API Gateways at the edge must verify limits per client tenant without introducing database query latencies. Using Veridot, limits published in CONFIG envelopes are read and evaluated locally in sub-milliseconds.'
            : 'Les passerelles API (Gateways) doivent valider les quotas par client sans interroger une base centrale. Les configurations publiées via les enveloppes CONFIG de Veridot sont résolues localement.'}
        </p>
        <CodeBlock code={RATELIMIT_CODE} language="java" title="API Gateway Rate Limiting" />
      </section>

      {/* Pattern 5: Zero-Trust Storage */}
      <section className="space-y-4">
        <div className="flex items-center gap-2">
          <Database className="text-violet-600 dark:text-violet-400" size={20} />
          <h2 className="text-xl font-bold text-slate-900 dark:text-white">
            {language === 'en' ? 'Pattern 5: Zero-Trust Temporary Storage Access' : 'Pattern 5 : Accès Temporaire au Stockage (Zero-Trust)'}
          </h2>
        </div>
        <p className="text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Give clients direct download access to private files in object storage without proxying file bytes through your application server. The backend issues a short-lived file ticket that the file server validates locally.'
            : 'Permettez aux clients de télécharger directement des fichiers privés sans faire transiter les octets par le serveur applicatif. Le backend émet un ticket temporaire validé localement par le serveur de fichiers.'}
        </p>
        <CodeBlock code={STORAGE_CODE} language="java" title="Zero-Trust Storage access" />
      </section>

      {/* Pattern 6: GDPR Consent */}
      <section className="space-y-4">
        <div className="flex items-center gap-2">
          <EyeOff className="text-violet-600 dark:text-violet-400" size={20} />
          <h2 className="text-xl font-bold text-slate-900 dark:text-white">
            {language === 'en' ? 'Pattern 6: Event-Driven Auditable Consent Verification (GDPR)' : 'Pattern 6 : Contrôle de Consentement GDPR Event-Driven'}
          </h2>
        </div>
        <p className="text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Track user consents for data processing. If a user withdraws consent, publish a revocation event. Distributed processing nodes check token activity in memory before executing data pipelines, building a secure audit trail.'
            : 'Suivez le consentement utilisateur pour le traitement de données. Si un utilisateur le retire, publiez un événement de révocation. Les nœuds de traitement valident localement le statut de consentement.'}
        </p>
        <CodeBlock code={CONSENT_CODE} language="java" title="GDPR Consent tracking" />
      </section>

      <Admonition type="success" title={language === 'en' ? 'Ready to Design?' : 'Prêt à concevoir ?'}>
        {language === 'en'
          ? 'Check the installation and Quick Start pages to start implementing these patterns in your cluster today.'
          : 'Consultez les pages d\'installation et de démarrage rapide pour intégrer ces patrons de conception dans votre architecture.'}
      </Admonition>
    </div>
  );
}
