import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const DIRECT_EXAMPLE = `DataSigner signer = new GenericSignerVerifier(broker, trustRoot, "auth-service", longTermKey, Algorithm.ED25519);

// DIRECT mode (default): JWT returned directly
String token = signer.sign("alice@example.com",
    BasicConfigurer.builder()
        .groupId("user-alice")
        .sequenceId("mobile-v2")   // optional; UUID auto-generated if omitted
        .validity(3600)            // TTL: 1 hour
        .build());

// Use the token directly in Authorization header:
// Authorization: Bearer eyJhbGciOiJFZERTQSJ9...`;

const INDIRECT_EXAMPLE = `// INDIRECT mode: messageId returned, token stored in broker
String messageId = signer.sign(sensitiveReport,
    BasicConfigurer.builder()
        .groupId("reports")
        .sequenceId("q2-2026")
        .validity(86400)               // TTL: 24 hours
        .distribution(DistributionMode.INDIRECT)
        .serializedBy(obj -> mapper.writeValueAsString(obj))
        .build());

// messageId format: "<protoVersion>:<groupId>:<sequenceId>"
// e.g., "4:reports:q2-2026"
// Store in your DB or pass to trusted services as an opaque reference`;

const POJO_EXAMPLE = `// Custom serializer for POJO payloads
ObjectMapper mapper = new ObjectMapper();

String token = signer.sign(
    new UserClaims("alice", Set.of("ADMIN", "READ")),
    BasicConfigurer.builder()
        .groupId("user-alice")
        .validity(3600)
        .serializedBy(obj -> mapper.writeValueAsString(obj))
        .build());`;

const ERROR_EXAMPLE = `try {
    String token = sv.sign(data, config);
} catch (DataSerializationException e) {
    // Your serializer threw an exception.
    // The payload could not be converted to a String.
    log.error("Serialization failure", e);
    response.sendError(500);
} catch (BrokerTransportException e) {
    // Kafka/DB unavailable. The KEY_EPOCH could not be published.
    // The signing operation failed — no token was issued.
    log.error("Broker transport failure", e);
    response.sendError(503);
} catch (SessionCapacityExceededException e) {
    // maxSessions reached + EvictionPolicy.REJECT.
    // No session was created. No token was issued.
    log.warn("Session limit for group={}: max={}", e.getGroupId(), e.getMaxSessions());
    response.sendError(429, "Too many active sessions");
}`;

const CONFIGURER_IMPL = `// Using the BasicConfigurer builder (recommended)
DataSigner.Configurer config = BasicConfigurer.builder()
    .groupId("user-123")            // REQUIRED
    .sequenceId("session-A")        // OPTIONAL (UUID if omitted)
    .validity(3600)                 // REQUIRED: seconds
    .distribution(DistributionMode.DIRECT) // OPTIONAL (DIRECT by default)
    .serializedBy(obj -> obj.toString())   // OPTIONAL (toString() by default)
    .build();`;

export function DataSignerPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">API Reference</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-1">DataSigner</h1>
        <p className="text-sm font-mono text-slate-500 dark:text-slate-400 mb-4">io.github.cyfko.veridot.core</p>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Functional interface for issuing cryptographically signed tokens tied to a payload and a time-bound validity window.'
            : 'Interface fonctionnelle pour émettre des tokens signés cryptographiquement liés à un payload et une fenêtre de validité temporelle.'}
        </p>
      </div>

      <div className="rounded-xl border border-slate-200 dark:border-slate-700 overflow-hidden">
        <div className="px-5 py-3 bg-slate-50 dark:bg-slate-800/50 border-b border-slate-200 dark:border-slate-700">
          <span className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">
            {language === 'en' ? 'Interface declaration' : 'Déclaration de l\'interface'}
          </span>
        </div>
        <CodeBlock
          code={`@FunctionalInterface
public interface DataSigner {
    String sign(Object data, Configurer configurer)
        throws DataSerializationException, BrokerTransportException;

    interface Configurer {
        String getGroupId();
        String getSequenceId();           // nullable
        DistributionMode getDistribution();
        long getDuration();               // seconds
        Function<Object, String> getSerializer();
    }
}`}
          language="java"
          showLineNumbers={false}
        />
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'The sign() method' : 'La méthode sign()'}
        </h2>
        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800/50">
                {(language === 'en' ? ['Parameter', 'Type', 'Required', 'Description'] : ['Paramètre', 'Type', 'Requis', 'Description']).map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800 bg-white dark:bg-slate-900">
              <tr>
                <td className="px-4 py-3 font-mono text-violet-700 dark:text-violet-300">data</td>
                <td className="px-4 py-3 font-mono text-sm text-slate-600 dark:text-slate-400">Object</td>
                <td className="px-4 py-3 text-emerald-600 dark:text-emerald-400 font-semibold">✓</td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{language === 'en' ? 'The payload to embed; must not be null' : 'Le payload à embarquer ; ne doit pas être null'}</td>
              </tr>
              <tr>
                <td className="px-4 py-3 font-mono text-violet-700 dark:text-violet-300">configurer</td>
                <td className="px-4 py-3 font-mono text-sm text-slate-600 dark:text-slate-400">Configurer</td>
                <td className="px-4 py-3 text-emerald-600 dark:text-emerald-400 font-semibold">✓</td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{language === 'en' ? 'Signing configuration; use BasicConfigurer.builder()' : 'Configuration de signature ; utiliser BasicConfigurer.builder()'}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div className="mt-4">
          <p className="text-sm font-semibold text-slate-700 dark:text-slate-300 mb-2">
            {language === 'en' ? 'Return value' : 'Valeur de retour'}
          </p>
          <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-slate-50 dark:bg-slate-800/50">
                  {['Mode', language === 'en' ? 'Returns' : 'Retourne', language === 'en' ? 'Format' : 'Format'].map(h => (
                    <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 dark:divide-slate-800 bg-white dark:bg-slate-900">
                <tr>
                  <td className="px-4 py-3 font-mono text-slate-700 dark:text-slate-300">DIRECT</td>
                  <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{language === 'en' ? 'Signed JWT token' : 'Token JWT signé'}</td>
                  <td className="px-4 py-3 font-mono text-sm text-slate-500">eyJhbGciOiJFZERTQSJ9...</td>
                </tr>
                <tr>
                  <td className="px-4 py-3 font-mono text-slate-700 dark:text-slate-300">INDIRECT</td>
                  <td className="px-4 py-3 text-slate-600 dark:text-slate-400">messageId</td>
                  <td className="px-4 py-3 font-mono text-sm text-slate-500">4:&lt;groupId&gt;:&lt;sequenceId&gt;</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'The Configurer interface' : 'L\'interface Configurer'}
        </h2>
        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800/50">
                {(language === 'en' ? ['Method', 'Required', 'Default', 'Description'] : ['Méthode', 'Requis', 'Défaut', 'Description']).map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800 bg-white dark:bg-slate-900">
              {[
                { method: 'getGroupId()', req: true, def: '—', desc: language === 'en' ? '1–125 printable chars, no ":", ",", "|", or whitespace' : '1–125 caractères imprimables, pas de ":", ",", "|" ou espace' },
                { method: 'getSequenceId()', req: false, def: 'UUID', desc: language === 'en' ? 'null triggers auto-generation' : 'null déclenche la génération automatique' },
                { method: 'getDuration()', req: true, def: '—', desc: language === 'en' ? 'Positive number of seconds (e.g., 3600)' : 'Nombre positif de secondes (ex. : 3600)' },
                { method: 'getDistribution()', req: false, def: 'DIRECT', desc: language === 'en' ? 'DIRECT or INDIRECT' : 'DIRECT ou INDIRECT' },
                { method: 'getSerializer()', req: false, def: 'toString()', desc: language === 'en' ? 'Function<Object, String> for payload serialization' : 'Function<Object, String> pour la sérialisation du payload' },
              ].map(row => (
                <tr key={row.method}>
                  <td className="px-4 py-3 font-mono text-violet-700 dark:text-violet-300 text-sm">{row.method}</td>
                  <td className="px-4 py-3">{row.req ? <span className="text-emerald-600 dark:text-emerald-400 font-bold">✓</span> : <span className="text-slate-400">—</span>}</td>
                  <td className="px-4 py-3 font-mono text-xs text-slate-500">{row.def}</td>
                  <td className="px-4 py-3 text-slate-600 dark:text-slate-400 text-sm">{row.desc}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <CodeBlock code={CONFIGURER_IMPL} language="java" title="BasicConfigurer builder" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Examples' : 'Exemples'}
        </h2>

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2">
          {language === 'en' ? 'DIRECT mode (default)' : 'Mode DIRECT (défaut)'}
        </h3>
        <CodeBlock code={DIRECT_EXAMPLE} language="java" title="sign() — DIRECT mode" />

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2 mt-6">
          {language === 'en' ? 'INDIRECT mode' : 'Mode INDIRECT'}
        </h3>
        <CodeBlock code={INDIRECT_EXAMPLE} language="java" title="sign() — INDIRECT mode" />

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2 mt-6">
          {language === 'en' ? 'POJO payload with custom serializer' : 'Payload POJO avec sérialiseur personnalisé'}
        </h3>
        <CodeBlock code={POJO_EXAMPLE} language="java" title="sign() with custom serializer" />

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2 mt-6">
          {language === 'en' ? 'Error handling' : 'Gestion des erreurs'}
        </h3>
        <CodeBlock code={ERROR_EXAMPLE} language="java" title="sign() error handling" />
      </section>

      <Admonition type="security" title={language === 'en' ? 'What sign() does internally' : 'Ce que sign() fait en interne'}>
        {language === 'en' ? (
          <ol className="list-decimal pl-4 space-y-1 mt-1">
            <li>Validates groupId and sequenceId identifiers</li>
            <li>Resolves configuration (LOCAL → SITE → GLOBAL → default)</li>
            <li>Captures the current ephemeral key snapshot (from the rotation service)</li>
            <li>Enforces capacity limits (if configured)</li>
            <li>Serializes the payload using the configured serializer</li>
            <li>Builds a signed JWT with the ephemeral private key</li>
            <li>Publishes KEY_EPOCH entry to the broker (signed with long-term key)</li>
            <li>Publishes LIVENESS=ACTIVE entry to the broker</li>
            <li>Starts the liveness renewal loop for this session</li>
          </ol>
        ) : (
          <ol className="list-decimal pl-4 space-y-1 mt-1">
            <li>Valide les identifiants groupId et sequenceId</li>
            <li>Résout la configuration (LOCAL → SITE → GLOBAL → défaut)</li>
            <li>Capture le snapshot de clé éphémère actuel (depuis le service de rotation)</li>
            <li>Applique les limites de capacité (si configurées)</li>
            <li>Sérialise le payload avec le sérialiseur configuré</li>
            <li>Construit un JWT signé avec la clé privée éphémère</li>
            <li>Publie l'entrée KEY_EPOCH sur le broker (signée avec la clé long terme)</li>
            <li>Publie l'entrée LIVENESS=ACTIVE sur le broker</li>
            <li>Démarre la boucle de renouvellement de liveness pour cette session</li>
          </ol>
        )}
      </Admonition>
    </div>
  );
}
