import { useApp } from '../context/AppContext';
import { Admonition } from '../components/Admonition';
import { Mermaid } from '../components/Mermaid';
import { CheckCircle, XCircle } from 'lucide-react';

const PROBLEM_DIAGRAM = `graph LR
    subgraph "Shared HMAC"
        A1[Service A] -- shared secret --> B1[Service B]
        style A1 fill:#fef3c7
        style B1 fill:#fef3c7
    end

    subgraph "Stateless JWT (no revocation)"
        A2[Issuer] -- signs --> JWT2[JWT ✓]
        JWT2 -- no way to revoke --> C2[Service B]
        style JWT2 fill:#fee2e2
    end

    subgraph "Centralized IdP"
        A3[Service A] -- verify request --> IDP[IdP]
        IDP -- validate --> A3
        style IDP fill:#fef3c7
    end

    subgraph "Veridot ✓"
        A4[Signer] --> B4[Broker]
        B4 --> C4[Local Cache]
        C4 -- sub-ms verify --> D4[Verifier]
        A4 -. LIVENESS=REVOKED .-> B4
        style A4 fill:#dcfce7
        style C4 fill:#dcfce7
        style D4 fill:#dcfce7
    end`;

export function WhyVeridotPage() {
  const { language } = useApp();

  return (
    <div className="space-y-10">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">
          {language === 'en' ? 'Getting Started' : 'Démarrage'}
        </p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Why Veridot?' : 'Pourquoi Veridot ?'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'The distributed authentication trilemma has existed since the rise of microservices. Veridot is the first library to solve all three constraints simultaneously.'
            : 'Le trilemme de l\'authentification distribuée existe depuis l\'essor des microservices. Veridot est la première bibliothèque à résoudre simultanément les trois contraintes.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'The problem every microservice faces' : 'Le problème que chaque microservice rencontre'}
        </h2>
        <Mermaid chart={PROBLEM_DIAGRAM} caption={language === 'en' ? 'Comparison of authentication approaches' : 'Comparaison des approches d\'authentification'} />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-6">
          {language === 'en' ? 'Every existing approach makes a compromise' : 'Chaque approche existante fait un compromis'}
        </h2>
        <div className="space-y-4">
          {(language === 'en' ? [
            {
              title: 'Shared HMAC',
              problem: 'Requires distributing a shared secret to every service that needs to verify tokens. This creates a key management nightmare at scale, and a single compromised service exposes all tokens.',
              pros: ['Revocable (delete the secret)', 'No network call for verification'],
              cons: ['Shared secret required', 'Secret compromise affects entire cluster'],
            },
            {
              title: 'Stateless RSA/ECDSA JWT',
              problem: 'The gold standard for stateless authentication — but fundamentally non-revocable. Once issued, a JWT is valid until its exp claim. There is no way to invalidate a stolen token before expiry without introducing state.',
              pros: ['No shared secret', 'No network call for verification'],
              cons: ['Not revocable without additional infrastructure', 'Expired tokens require waiting'],
            },
            {
              title: 'Centralized IdP call (OAuth introspection, PASETO)',
              problem: 'Fully revocable and requires no shared secret, but every token verification requires a network call to the authorization server. This creates a latency dependency and a single point of failure.',
              pros: ['Revocable', 'No shared secret'],
              cons: ['Every verify = network call', 'IdP is a single point of failure', 'High latency at scale'],
            },
          ] : [
            {
              title: 'HMAC partagé',
              problem: 'Nécessite de distribuer un secret partagé à chaque service qui doit vérifier les tokens. Cela crée un cauchemar de gestion des clés à grande échelle, et un seul service compromis expose tous les tokens.',
              pros: ['Révocable (supprimer le secret)', 'Pas d\'appel réseau pour la vérification'],
              cons: ['Secret partagé requis', 'La compromission du secret affecte tout le cluster'],
            },
            {
              title: 'JWT RSA/ECDSA stateless',
              problem: 'Le standard d\'or pour l\'authentification stateless — mais fondamentalement non révocable. Une fois émis, un JWT est valide jusqu\'à sa revendication exp. Il n\'existe aucun moyen d\'invalider un token volé avant expiration sans introduire un état.',
              pros: ['Pas de secret partagé', 'Pas d\'appel réseau pour la vérification'],
              cons: ['Non révocable sans infrastructure supplémentaire', 'Les tokens expirés nécessitent d\'attendre'],
            },
            {
              title: 'Appel IdP centralisé (introspection OAuth, PASETO)',
              problem: 'Entièrement révocable et ne nécessite pas de secret partagé, mais chaque vérification de token nécessite un appel réseau au serveur d\'autorisation. Cela crée une dépendance de latence et un point de défaillance unique.',
              pros: ['Révocable', 'Pas de secret partagé'],
              cons: ['Chaque vérification = appel réseau', 'IdP est un point de défaillance unique', 'Haute latence à grande échelle'],
            },
          ]).map(approach => (
            <div key={approach.title} className="border border-slate-200 dark:border-slate-700 rounded-xl overflow-hidden">
              <div className="px-5 py-3 bg-slate-50 dark:bg-slate-800/50 border-b border-slate-200 dark:border-slate-700">
                <h3 className="font-semibold text-slate-900 dark:text-white">{approach.title}</h3>
              </div>
              <div className="p-5">
                <p className="text-sm text-slate-600 dark:text-slate-400 mb-4">{approach.problem}</p>
                <div className="grid sm:grid-cols-2 gap-4">
                  <div>
                    <p className="text-xs font-semibold text-emerald-600 dark:text-emerald-400 mb-2 uppercase tracking-wider">
                      {language === 'en' ? 'Advantages' : 'Avantages'}
                    </p>
                    {approach.pros.map(p => (
                      <div key={p} className="flex items-start gap-2 mb-1">
                        <CheckCircle size={13} className="text-emerald-500 flex-shrink-0 mt-0.5" />
                        <span className="text-sm text-slate-600 dark:text-slate-400">{p}</span>
                      </div>
                    ))}
                  </div>
                  <div>
                    <p className="text-xs font-semibold text-red-600 dark:text-red-400 mb-2 uppercase tracking-wider">
                      {language === 'en' ? 'Limitations' : 'Limitations'}
                    </p>
                    {approach.cons.map(c => (
                      <div key={c} className="flex items-start gap-2 mb-1">
                        <XCircle size={13} className="text-red-400 flex-shrink-0 mt-0.5" />
                        <span className="text-sm text-slate-600 dark:text-slate-400">{c}</span>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            </div>
          ))}
        </div>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'How Veridot solves all three' : 'Comment Veridot résout les trois'}
        </h2>
        <div className="rounded-xl border-2 border-violet-300 dark:border-violet-700 overflow-hidden">
          <div className="px-5 py-3 bg-violet-50 dark:bg-violet-950/50 border-b border-violet-200 dark:border-violet-800">
            <h3 className="font-semibold text-violet-800 dark:text-violet-200">★ Veridot</h3>
          </div>
          <div className="p-5 space-y-4">
            {(language === 'en' ? [
              { title: 'No shared secret', desc: 'Each issuing service generates its own ephemeral asymmetric key pair (RSA-3072, ECDSA, or Ed25519 by default). Verifiers receive only the public key, distributed through the broker — never a private key or symmetric secret.' },
              { title: 'No network call at verify time', desc: 'The KEY_EPOCH and LIVENESS entries are propagated to a local RocksDB cache (via Kafka) or a local DB replica. Verification reads directly from this local store — sub-millisecond, no round-trip.' },
              { title: 'Instant revocation', desc: 'Revoking a session publishes a signed LIVENESS=REVOKED entry. All verifiers reject the token as soon as they observe this entry — typically within one Kafka consumer poll cycle, bounded by the reconciliation interval.' },
              { title: 'Broker-untrusted by construction', desc: 'Even with full write access to Kafka or the SQL database, an attacker cannot forge a valid KEY_EPOCH entry. Every entry carries a long-term signature verified against the TrustRoot — independent of the broker.' },
            ] : [
              { title: 'Pas de secret partagé', desc: 'Chaque service émetteur génère sa propre paire de clés asymétriques éphémères (RSA-3072, ECDSA ou Ed25519 par défaut). Les vérificateurs reçoivent uniquement la clé publique, distribuée via le broker — jamais une clé privée ou un secret symétrique.' },
              { title: 'Pas d\'appel réseau à la vérification', desc: 'Les entrées KEY_EPOCH et LIVENESS sont propagées vers un cache RocksDB local (via Kafka) ou un réplica DB local. La vérification lit directement depuis ce store local — sous-milliseconde, pas d\'aller-retour.' },
              { title: 'Révocation instantanée', desc: 'Révoquer une session publie une entrée LIVENESS=REVOKED signée. Tous les vérificateurs rejettent le token dès qu\'ils observent cette entrée — typiquement dans un cycle de poll du consommateur Kafka, borné par l\'intervalle de réconciliation.' },
              { title: 'Broker non fiable par construction', desc: 'Même avec un accès complet en écriture à Kafka ou la base SQL, un attaquant ne peut pas forger une entrée KEY_EPOCH valide. Chaque entrée porte une signature long terme vérifiée contre la TrustRoot — indépendamment du broker.' },
            ]).map(item => (
              <div key={item.title} className="flex items-start gap-3">
                <div className="flex-shrink-0 h-6 w-6 rounded-full bg-violet-600 flex items-center justify-center mt-0.5">
                  <CheckCircle size={13} className="text-white" />
                </div>
                <div>
                  <p className="font-semibold text-slate-900 dark:text-white text-sm">{item.title}</p>
                  <p className="text-sm text-slate-600 dark:text-slate-400 mt-0.5">{item.desc}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>

      <Admonition type="info" title={language === 'en' ? 'When NOT to use Veridot' : 'Quand NE PAS utiliser Veridot'}>
        {language === 'en' ? (
          <ul className="list-disc pl-4 space-y-1 mt-1">
            <li>Single-service applications with no inter-service authentication requirements</li>
            <li>Environments where you cannot run Kafka or a SQL database accessible by all services</li>
            <li>When you need human-readable token introspection without a shared library</li>
            <li>When Java 25+ is not available and you need the core API</li>
          </ul>
        ) : (
          <ul className="list-disc pl-4 space-y-1 mt-1">
            <li>Applications mono-service sans exigences d\'authentification inter-services</li>
            <li>Environnements où vous ne pouvez pas exécuter Kafka ou une base SQL accessible par tous les services</li>
            <li>Quand vous avez besoin d\'une introspection de token lisible par l\'homme sans bibliothèque partagée</li>
            <li>Quand Java 25+ n\'est pas disponible et que vous avez besoin de l\'API centrale</li>
          </ul>
        )}
      </Admonition>
    </div>
  );
}
