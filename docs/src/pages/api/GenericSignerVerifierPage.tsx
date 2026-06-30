import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const CONSTRUCTOR_MINIMAL = `// Minimal constructor — no session limit, Ed25519 algorithm
var sv = new GenericSignerVerifier(
    broker,
    trustRoot,
    "auth-service",      // issuerId
    longTermPrivateKey,
    Algorithm.ED25519    // envelope signature algorithm (recommended)
);`;

const CONSTRUCTOR_WITH_CAPACITY = `// With session capacity management
var sv = new GenericSignerVerifier(
    broker,
    trustRoot,
    "auth-service",
    longTermPrivateKey,
    Algorithm.ED25519,
    5,                      // maxSessions per group
    EvictionPolicy.FIFO     // eviction strategy when maxSessions reached
);`;

const CONSTRUCTOR_WITH_WATERMARK = `// With a custom WatermarkStore (for persistent watermarks across restarts)
WatermarkStore store = new FileWatermarkStore(
    new File("/var/lib/veridot/watermarks"),
    hmacKey   // for tamper-protection
);

var sv = new GenericSignerVerifier(
    broker,
    trustRoot,
    "auth-service",
    longTermPrivateKey,
    Algorithm.ED25519,
    store
);`;

const SIGN_EXAMPLE = `// sign() implements DataSigner
String token = sv.sign("alice@example.com",
    BasicConfigurer.builder()
        .groupId("user-alice")
        .sequenceId("mobile-v2")
        .validity(3600)
        .build());`;

const VERIFY_EXAMPLE = `// verify() implements TokenVerifier
VerifiedData<String> result = sv.verify(token, s -> s);`;

const REVOKE_EXAMPLE = `// revoke() implements TokenRevoker
sv.revoke("user-alice", "mobile-v2");  // specific session
sv.revoke("user-alice", null);          // all sessions in group`;

const TRACKER_EXAMPLE = `// hasActiveToken() implements TokenTracker
boolean active1 = sv.hasActiveToken("user-alice");          // by groupId
boolean active2 = sv.hasActiveToken(token);                 // by JWT
boolean active3 = sv.hasActiveToken("4:user-alice:mobile"); // by messageId`;

const CONFIG_PUBLISH = `// publishConfig() — not part of any interface, available on GenericSignerVerifier
sv.publishConfig(
    ConfigScope.GLOBAL,    // or LOCAL, SITE
    null,                  // scopeId (required for LOCAL/SITE)
    10,                    // maxSessions
    EvictionPolicy.LRU,    // eviction policy
    1800,                  // defaultTtlSeconds (30 min)
    86400                  // validitySeconds (config expires after 24h)
);`;

const CLOSE_EXAMPLE = `// GenericSignerVerifier implements AutoCloseable
try (var sv = new GenericSignerVerifier(broker, trust, "svc", key, Algorithm.ED25519)) {
    // use sv...
} // automatically stops key rotation, liveness renewal, reconciliation`;

const SPRING_BEAN = `@Bean
public GenericSignerVerifier veridotSignerVerifier(
    Broker broker, TrustRoot trust, PrivateKey longTermKey) {
    return new GenericSignerVerifier(
        broker, trust,
        "auth-service",
        longTermKey,
        Algorithm.ED25519,
        5,
        EvictionPolicy.FIFO
    );
}

// Expose each interface as a separate bean for clean injection
@Bean DataSigner dataSigner(GenericSignerVerifier sv) { return sv; }
@Bean TokenVerifier tokenVerifier(GenericSignerVerifier sv) { return sv; }
@Bean TokenRevoker tokenRevoker(GenericSignerVerifier sv) { return sv; }
@Bean TokenTracker tokenTracker(GenericSignerVerifier sv) { return sv; }`;

export function GenericSignerVerifierPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">API Reference</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-1">GenericSignerVerifier</h1>
        <p className="text-sm font-mono text-slate-500 dark:text-slate-400 mb-4">io.github.cyfko.veridot.core.impl</p>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'The primary implementation class. Implements DataSigner, TokenVerifier, TokenRevoker, and TokenTracker. Also implements AutoCloseable for safe resource management.'
            : 'La classe d\'implémentation principale. Implémente DataSigner, TokenVerifier, TokenRevoker et TokenTracker. Implémente également AutoCloseable pour une gestion sûre des ressources.'}
        </p>
      </div>

      <div className="rounded-xl border border-slate-200 dark:border-slate-700 overflow-hidden">
        <div className="px-5 py-3 bg-slate-50 dark:bg-slate-800/50 border-b border-slate-200 dark:border-slate-700">
          <span className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">
            {language === 'en' ? 'Class declaration' : 'Déclaration de classe'}
          </span>
        </div>
        <CodeBlock
          code={`public class GenericSignerVerifier
    implements DataSigner, TokenVerifier, TokenRevoker, TokenTracker, AutoCloseable {
    // ...
}`}
          language="java"
          showLineNumbers={false}
        />
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Constructors' : 'Constructeurs'}
        </h2>

        <Admonition type="info" title={language === 'en' ? 'Algorithm parameter required in V4' : 'Paramètre Algorithm requis en V4'}>
          {language === 'en'
            ? 'The V4 API requires an explicit Algorithm enum value for the envelope signature. The deprecated constructors accept a byte code for backward compatibility. Use Algorithm.ED25519 for new implementations (recommended by NIST SP 800-186).'
            : 'L\'API V4 nécessite une valeur enum Algorithm explicite pour la signature de l\'enveloppe. Les constructeurs dépréciés acceptent un code octet pour la compatibilité ascendante. Utilisez Algorithm.ED25519 pour les nouvelles implémentations (recommandé par NIST SP 800-186).'}
        </Admonition>

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2 mt-4">
          {language === 'en' ? 'Minimal (no session limit)' : 'Minimal (sans limite de session)'}
        </h3>
        <CodeBlock code={CONSTRUCTOR_MINIMAL} language="java" title="GenericSignerVerifier — minimal constructor" />

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2 mt-6">
          {language === 'en' ? 'With session capacity management' : 'Avec gestion de capacité de session'}
        </h3>
        <CodeBlock code={CONSTRUCTOR_WITH_CAPACITY} language="java" title="GenericSignerVerifier — with session capacity" />

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2 mt-6">
          {language === 'en' ? 'With persistent WatermarkStore' : 'Avec WatermarkStore persistant'}
        </h3>
        <CodeBlock code={CONSTRUCTOR_WITH_WATERMARK} language="java" title="GenericSignerVerifier — with WatermarkStore" />

        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700 mt-4">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800/50">
                {(language === 'en' ? ['Parameter', 'Type', 'Required', 'Description'] : ['Paramètre', 'Type', 'Requis', 'Description']).map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800 bg-white dark:bg-slate-900">
              {[
                { param: 'broker', type: 'Broker', req: true, desc: language === 'en' ? 'Transport and storage layer' : 'Couche de transport et stockage' },
                { param: 'trustRoot', type: 'TrustRoot', req: true, desc: language === 'en' ? 'Cryptographic trust resolver' : 'Résolveur de confiance cryptographique' },
                { param: 'issuerId', type: 'String', req: true, desc: language === 'en' ? 'Non-blank unique identifier for this processor' : 'Identifiant unique non vide pour ce processeur' },
                { param: 'longTermKey', type: 'PrivateKey', req: true, desc: language === 'en' ? 'Signs KEY_EPOCH envelopes; must match the TrustRoot\'s expected algorithm' : 'Signe les enveloppes KEY_EPOCH ; doit correspondre à l\'algorithme attendu par TrustRoot' },
                { param: 'envelopeSigAlg', type: 'Algorithm', req: true, desc: language === 'en' ? 'Envelope signature algorithm (ED25519 recommended)' : 'Algorithme de signature d\'enveloppe (ED25519 recommandé)' },
                { param: 'maxSessions', type: 'int', req: false, desc: language === 'en' ? 'Session limit per group; -1 = unlimited' : 'Limite de session par groupe ; -1 = illimité' },
                { param: 'evictionPolicy', type: 'EvictionPolicy', req: false, desc: language === 'en' ? 'Strategy when maxSessions reached: FIFO, LIFO, LRU, REJECT' : 'Stratégie quand maxSessions est atteint : FIFO, LIFO, LRU, REJECT' },
                { param: 'watermarkStore', type: 'WatermarkStore', req: false, desc: language === 'en' ? 'Persistent store for version watermarks (survives restarts)' : 'Store persistant pour les watermarks de version (survit aux redémarrages)' },
              ].map(row => (
                <tr key={row.param}>
                  <td className="px-4 py-3 font-mono text-violet-700 dark:text-violet-300 text-sm">{row.param}</td>
                  <td className="px-4 py-3 font-mono text-sm text-slate-600 dark:text-slate-400">{row.type}</td>
                  <td className="px-4 py-3">{row.req ? <span className="text-emerald-600 dark:text-emerald-400 font-bold">✓</span> : <span className="text-slate-400">opt</span>}</td>
                  <td className="px-4 py-3 text-slate-600 dark:text-slate-400 text-sm">{row.desc}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Implemented methods' : 'Méthodes implémentées'}
        </h2>

        <div className="grid sm:grid-cols-2 gap-4">
          {[
            { title: 'sign()', interface: 'DataSigner', code: SIGN_EXAMPLE },
            { title: 'verify()', interface: 'TokenVerifier', code: VERIFY_EXAMPLE },
            { title: 'revoke()', interface: 'TokenRevoker', code: REVOKE_EXAMPLE },
            { title: 'hasActiveToken()', interface: 'TokenTracker', code: TRACKER_EXAMPLE },
          ].map(m => (
            <div key={m.title} className="border border-slate-200 dark:border-slate-700 rounded-xl overflow-hidden">
              <div className="px-4 py-2 bg-slate-50 dark:bg-slate-800/50 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between">
                <span className="font-mono font-semibold text-sm text-slate-800 dark:text-slate-200">{m.title}</span>
                <span className="text-xs text-slate-400 font-mono">from {m.interface}</span>
              </div>
              <CodeBlock code={m.code} language="java" showLineNumbers={false} />
            </div>
          ))}
        </div>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          publishConfig()
        </h2>
        <p className="text-slate-600 dark:text-slate-400 mb-3 text-sm">
          {language === 'en'
            ? 'Not part of any interface. Available directly on GenericSignerVerifier. Publishes a signed CONFIG entry at a given scope.'
            : 'Ne fait partie d\'aucune interface. Disponible directement sur GenericSignerVerifier. Publie une entrée CONFIG signée à un scope donné.'}
        </p>
        <CodeBlock code={CONFIG_PUBLISH} language="java" title="publishConfig()" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Resource management (AutoCloseable)' : 'Gestion des ressources (AutoCloseable)'}
        </h2>
        <CodeBlock code={CLOSE_EXAMPLE} language="java" title="try-with-resources" />
        <Admonition type="warning" title={language === 'en' ? 'Always close GenericSignerVerifier' : 'Toujours fermer GenericSignerVerifier'}>
          {language === 'en'
            ? 'close() stops the key rotation scheduler, liveness renewal loops, reconciliation tasks, and saves the current watermark snapshot. Failing to close may cause resource leaks and unsaved watermarks.'
            : 'close() arrête le planificateur de rotation des clés, les boucles de renouvellement de liveness, les tâches de réconciliation, et sauvegarde le snapshot de watermark actuel. Ne pas fermer peut causer des fuites de ressources et des watermarks non sauvegardés.'}
        </Admonition>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">Spring Boot</h2>
        <CodeBlock code={SPRING_BEAN} language="java" title="GenericSignerVerifier as Spring Bean" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Internal components' : 'Composants internes'}
        </h2>
        <div className="grid sm:grid-cols-2 gap-3 text-sm">
          {[
            { comp: 'KeyRotationService', desc: language === 'en' ? 'Manages ephemeral key pair rotation (default: Ed25519, interval configurable via VDOT_KEYS_ROTATION_MINUTES)' : 'Gère la rotation des paires de clés éphémères (défaut : Ed25519, intervalle configurable via VDOT_KEYS_ROTATION_MINUTES)' },
            { comp: 'LivenessManager', desc: language === 'en' ? 'Publishes ACTIVE/REVOKED entries and manages renewal loops for active sessions' : 'Publie les entrées ACTIVE/REVOKED et gère les boucles de renouvellement pour les sessions actives' },
            { comp: 'ReconciliationManager', desc: language === 'en' ? 'Performs periodic full-scope snapshots to detect missed entries (§11.4)' : 'Effectue des snapshots périodiques de scope complet pour détecter les entrées manquées (§11.4)' },
            { comp: 'CapabilityVerifier', desc: language === 'en' ? 'Validates CAPABILITY entry chains before processing CONFIG/FENCE entries' : 'Valide les chaînes d\'entrées CAPABILITY avant de traiter les entrées CONFIG/FENCE' },
            { comp: 'ConfigResolver', desc: language === 'en' ? 'Resolves configuration from LOCAL → SITE → GLOBAL → default hierarchy' : 'Résout la configuration depuis la hiérarchie LOCAL → SITE → GLOBAL → défaut' },
            { comp: 'CapacityManager', desc: language === 'en' ? 'Enforces session capacity limits and eviction policies' : 'Applique les limites de capacité de session et les politiques d\'éviction' },
            { comp: 'VersionWatermark', desc: language === 'en' ? 'Per-EntryId monotonic version tracking (prevents replay/rollback)' : 'Suivi de version monotone par EntryId (prévient la relecture/rollback)' },
            { comp: 'EntryVerifier', desc: language === 'en' ? 'Orchestrates the 10-step KEY_EPOCH verification pipeline' : 'Orchestre le pipeline de vérification KEY_EPOCH en 10 étapes' },
          ].map(c => (
            <div key={c.comp} className="p-3 border border-slate-200 dark:border-slate-700 rounded-lg bg-white dark:bg-slate-900">
              <code className="text-violet-700 dark:text-violet-300 font-semibold text-sm">{c.comp}</code>
              <p className="text-slate-500 dark:text-slate-400 mt-1 text-xs leading-relaxed">{c.desc}</p>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
