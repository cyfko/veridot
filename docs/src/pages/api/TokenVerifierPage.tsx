import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';
import { Mermaid } from '../../components/Mermaid';

const VERIFY_FLOW = `sequenceDiagram
    participant C as Caller
    participant TV as TokenVerifier
    participant Cache as Local Cache
    participant TR as TrustRoot

    C->>TV: verify(token, deserializer)
    TV->>TV: 1. Resolve token format (JWT vs messageId)
    TV->>Cache: 2. Retrieve KEY_EPOCH envelope
    Cache-->>TV: envelope bytes
    TV->>TV: 3. Structural validation (magic, protoVersion, entryType)
    TV->>TR: 4. Resolve issuer → public key
    TR-->>TV: TrustIdentity
    TV->>TV: 4. Verify envelope signature
    TV->>TV: 5. Capability chain check (CAPABILITY entries)
    TV->>TV: 6. Monotonic version watermark check
    TV->>TV: 7. Temporal validity check (validFrom/validUntil ±5min)
    TV->>Cache: 8. Check LIVENESS=ACTIVE for session
    Cache-->>TV: liveness status
    TV->>TV: 9. Verify JWT cryptographic signature (ephemeral key)
    TV->>TV: 10. Deserialize payload
    TV-->>C: VerifiedData<T>`;

const VERIFY_BASIC = `TokenVerifier verifier = new GenericSignerVerifier(broker, trustRoot, "svc", key, Algorithm.ED25519);

// String payload
VerifiedData<String> result = verifier.verify(token, s -> s);
System.out.println(result.data());       // original payload
System.out.println(result.groupId());    // e.g., "user-alice"
System.out.println(result.sequenceId()); // e.g., "mobile-v2"`;

const VERIFY_POJO = `// POJO deserialization using Jackson
ObjectMapper mapper = new ObjectMapper();
VerifiedData<UserClaims> result = verifier.verify(token,
    json -> mapper.readValue(json, UserClaims.class));

String userId = result.groupId();
UserClaims claims = result.data();`;

const VERIFY_HELPER = `// Using the built-in Jackson helper from BasicConfigurer
VerifiedData<UserClaims> result = verifier.verify(token,
    BasicConfigurer.deserializer(UserClaims.class));`;

const VERIFY_MESSAGE_ID = `// Works with messageId (INDIRECT mode) too — same API
VerifiedData<ReportData> result = verifier.verify(
    "4:reports:q2-2026",
    json -> mapper.readValue(json, ReportData.class));`;

const VERIFY_ERROR = `try {
    VerifiedData<String> result = verifier.verify(token, s -> s);
    // ... success path
} catch (BrokerExtractionException e) {
    // Covers ALL verification failures:
    // • Token expired (now > validUntil)
    // • Session revoked (LIVENESS=REVOKED found)
    // • TrustRoot resolution failed (unknown issuer)
    // • Envelope signature invalid (tampered entry)
    // • JWT signature invalid (tampered token)
    // • LIVENESS entry absent (session never activated)
    // • Capability not found or expired
    // • Monotonic version violation (stale entry)
    // • Broker unavailable (transport error)
    response.sendError(401);
} catch (DataDeserializationException e) {
    // Token is cryptographically valid but the payload
    // could not be deserialized by your deserializer function.
    log.error("Deserialization failure", e);
    response.sendError(500);
}`;

const STALENESS = `// Check reconciliation staleness for a scope (advanced)
long staleMs = verifier.getReconciliationStalenessMs("group:user-alice");
if (staleMs > 0) {
    log.warn("Reconciliation stale for scope, delay={}ms", staleMs);
}
// Returns -1 if the scope has never been reconciled or reconciliation is inactive`;

export function TokenVerifierPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">API Reference</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-1">TokenVerifier</h1>
        <p className="text-sm font-mono text-slate-500 dark:text-slate-400 mb-4">io.github.cyfko.veridot.core</p>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Functional interface for verifying signed tokens and extracting the embedded payload. Accepts both JWT tokens (DIRECT mode) and messageIds (INDIRECT mode).'
            : 'Interface fonctionnelle pour vérifier les tokens signés et extraire le payload embarqué. Accepte à la fois les tokens JWT (mode DIRECT) et les messageIds (mode INDIRECT).'}
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
public interface TokenVerifier {
    <T> VerifiedData<T> verify(String token, Function<String, T> deserializer)
        throws BrokerExtractionException, DataDeserializationException;

    // Default method — override not required
    default long getReconciliationStalenessMs(String scope) {
        return -1L;
    }
}`}
          language="java"
          showLineNumbers={false}
        />
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Verification pipeline (10 steps)' : 'Pipeline de vérification (10 étapes)'}
        </h2>
        <Mermaid chart={VERIFY_FLOW} caption={language === 'en' ? 'Every verify() call runs all 10 steps in order' : 'Chaque appel verify() exécute les 10 étapes dans l\'ordre'} />
        <Admonition type="security" title={language === 'en' ? 'Steps 4–7 cannot be skipped or reordered' : 'Les étapes 4–7 ne peuvent pas être ignorées ou réordonnées'}>
          {language === 'en'
            ? 'Protocol V4 §5.4 mandates that steps 4 (TrustRoot resolution + signature), 5 (capability check), 6 (version watermark), and 7 (liveness) each independently produce rejection on failure. None may be skipped, reordered after step 8 (JWT verification), or replaced by an alternate check. GenericSignerVerifier enforces this order unconditionally.'
            : 'Le Protocole V4 §5.4 impose que les étapes 4 (résolution TrustRoot + signature), 5 (vérification de capacité), 6 (watermark de version) et 7 (liveness) produisent chacune indépendamment un rejet en cas d\'échec. Aucune ne peut être ignorée, réordonnée après l\'étape 8 (vérification JWT) ou remplacée par un contrôle alternatif. GenericSignerVerifier applique cet ordre inconditionnellement.'}
        </Admonition>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Method reference' : 'Référence des méthodes'}
        </h2>
        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800/50">
                {(language === 'en' ? ['Method', 'Description', 'Throws'] : ['Méthode', 'Description', 'Lance']).map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800 bg-white dark:bg-slate-900">
              <tr>
                <td className="px-4 py-3 font-mono text-violet-700 dark:text-violet-300 text-sm">verify(token, deserializer)</td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-400 text-sm">{language === 'en' ? 'Run full 10-step verification pipeline, deserialize payload, return VerifiedData<T>' : 'Exécuter le pipeline de vérification complet en 10 étapes, désérialiser le payload, retourner VerifiedData<T>'}</td>
                <td className="px-4 py-3 font-mono text-xs text-red-600 dark:text-red-400">BrokerExtractionException, DataDeserializationException</td>
              </tr>
              <tr>
                <td className="px-4 py-3 font-mono text-violet-700 dark:text-violet-300 text-sm">getReconciliationStalenessMs(scope)</td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-400 text-sm">{language === 'en' ? 'Returns delay in ms since last reconciliation for scope, or -1 if never reconciled' : 'Retourne le délai en ms depuis la dernière réconciliation pour le scope, ou -1 si jamais réconcilié'}</td>
                <td className="px-4 py-3 font-mono text-xs text-slate-400">—</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Examples' : 'Exemples'}
        </h2>

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2">
          {language === 'en' ? 'Basic string payload' : 'Payload string basique'}
        </h3>
        <CodeBlock code={VERIFY_BASIC} language="java" title="verify() — string payload" />

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2 mt-6">
          {language === 'en' ? 'POJO deserialization' : 'Désérialisation POJO'}
        </h3>
        <CodeBlock code={VERIFY_POJO} language="java" title="verify() — POJO with Jackson" />

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2 mt-6">
          {language === 'en' ? 'Built-in Jackson helper' : 'Helper Jackson intégré'}
        </h3>
        <CodeBlock code={VERIFY_HELPER} language="java" title="verify() — BasicConfigurer.deserializer()" />

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2 mt-6">
          {language === 'en' ? 'Verifying a messageId (INDIRECT mode)' : 'Vérification d\'un messageId (mode INDIRECT)'}
        </h3>
        <CodeBlock code={VERIFY_MESSAGE_ID} language="java" title="verify() — messageId" />

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2 mt-6">
          {language === 'en' ? 'Error handling' : 'Gestion des erreurs'}
        </h3>
        <CodeBlock code={VERIFY_ERROR} language="java" title="verify() — comprehensive error handling" />

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2 mt-6">
          {language === 'en' ? 'Reconciliation staleness (advanced)' : 'Staleness de réconciliation (avancé)'}
        </h3>
        <CodeBlock code={STALENESS} language="java" title="getReconciliationStalenessMs()" />
      </section>
    </div>
  );
}
