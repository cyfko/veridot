import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const INTERFACE_CODE = `public interface TokenRevoker {
    void revoke(String groupId, String sequenceId);
}`;

const SESSION_REVOKE = `TokenRevoker revoker = new GenericSignerVerifier(broker, trust, "svc", key, Algorithm.ED25519);

// Revoke ONE specific session
// After this call, verify(token) for this session will throw BrokerExtractionException
revoker.revoke("user-alice", "mobile-v2");

// Typical pattern: revoke using identifiers from VerifiedData
VerifiedData<String> result = verifier.verify(token, s -> s);
revoker.revoke(result.groupId(), result.sequenceId());`;

const GROUP_REVOKE = `// Revoke ALL sessions for a group (null sequenceId)
// Use case: user password change, account suspension, security breach
revoker.revoke("user-alice", null);

// After this call:
// - ALL active sessions for "user-alice" are revoked
// - Any concurrent verify() for those sessions will fail with BrokerExtractionException
// - New sign() can create new sessions immediately`;

const SPRING_REVOKE = `// Spring Boot: inject TokenRevoker independently
@Service
@RequiredArgsConstructor
public class SessionService {

    private final TokenRevoker revoker;

    public void logout(String userId, String deviceId) {
        revoker.revoke("user-" + userId, deviceId);
    }

    public void logoutAllDevices(String userId) {
        revoker.revoke("user-" + userId, null);
    }

    public void suspendAccount(String userId) {
        // Revoke all sessions AND prevent new ones
        revoker.revoke("user-" + userId, null);
        // Then flag the userId as suspended in your application DB
    }
}`;

const ERROR_HANDLING = `// revoke() is void — it does not throw for missing sessions.
// If the sequenceId doesn't exist, the call is a no-op.

try {
    revoker.revoke("user-alice", "mobile-v2");
} catch (IllegalArgumentException e) {
    // Only thrown if groupId is null or blank
    // sequenceId validation: null is permitted (revoke all)
    // but non-null values must follow identifier constraints
    log.error("Invalid arguments: {}", e.getMessage());
}
// No exception thrown if session was not found (idempotent)`;

export function TokenRevokerPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">API Reference</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-1">TokenRevoker</h1>
        <p className="text-sm font-mono text-slate-500 dark:text-slate-400 mb-4">io.github.cyfko.veridot.core</p>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Interface for invalidating previously issued tokens. Revocation is expressed in terms of Protocol V4 identifiers (groupId, sequenceId) rather than opaque token strings.'
            : 'Interface pour invalider les tokens précédemment émis. La révocation s\'exprime en termes d\'identifiants Protocole V4 (groupId, sequenceId) plutôt qu\'en chaînes de token opaques.'}
        </p>
      </div>

      <div className="rounded-xl border border-slate-200 dark:border-slate-700 overflow-hidden">
        <div className="px-5 py-3 bg-slate-50 dark:bg-slate-800/50 border-b border-slate-200 dark:border-slate-700">
          <span className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">
            {language === 'en' ? 'Interface declaration' : 'Déclaration de l\'interface'}
          </span>
        </div>
        <CodeBlock code={INTERFACE_CODE} language="java" showLineNumbers={false} />
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Method reference' : 'Référence de méthode'}
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
                <td className="px-4 py-3 font-mono text-violet-700 dark:text-violet-300">groupId</td>
                <td className="px-4 py-3 font-mono text-sm text-slate-600 dark:text-slate-400">String</td>
                <td className="px-4 py-3 text-emerald-600 font-bold">✓</td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{language === 'en' ? 'The group whose session(s) to revoke; must not be null or blank' : 'Le groupe dont les session(s) doivent être révoquées ; ne doit pas être null ou vide'}</td>
              </tr>
              <tr>
                <td className="px-4 py-3 font-mono text-violet-700 dark:text-violet-300">sequenceId</td>
                <td className="px-4 py-3 font-mono text-sm text-slate-600 dark:text-slate-400">String</td>
                <td className="px-4 py-3 text-slate-400">opt</td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{language === 'en' ? 'Specific session to revoke, or null to revoke ALL sessions of the group' : 'Session spécifique à révoquer, ou null pour révoquer TOUTES les sessions du groupe'}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <Admonition type="info" title={language === 'en' ? 'Revocation propagation timing' : 'Délai de propagation de révocation'}>
        {language === 'en'
          ? 'revoke() publishes a signed LIVENESS=REVOKED entry to the broker. Verifiers observe this entry on their next broker read (Kafka: next consumer poll; SQL: next polling interval). The operation is not instantaneous across all nodes — it is bounded by the reconciliation interval (default 60 minutes, usually much less for active consumers). Protocol V4 §8.'
          : 'revoke() publie une entrée LIVENESS=REVOKED signée sur le broker. Les vérificateurs observent cette entrée lors de leur prochain lecture broker (Kafka : prochain poll du consommateur ; SQL : prochain intervalle de polling). L\'opération n\'est pas instantanée sur tous les nœuds — elle est bornée par l\'intervalle de réconciliation (défaut 60 minutes, généralement bien moins pour les consommateurs actifs). Protocole V4 §8.'}
      </Admonition>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Examples' : 'Exemples'}
        </h2>

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2">
          {language === 'en' ? 'Session revocation' : 'Révocation de session'}
        </h3>
        <CodeBlock code={SESSION_REVOKE} language="java" title="revoke() — single session" />

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2 mt-6">
          {language === 'en' ? 'Group revocation (logout all devices)' : 'Révocation de groupe (déconnexion de tous les appareils)'}
        </h3>
        <CodeBlock code={GROUP_REVOKE} language="java" title="revoke() — entire group" />

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2 mt-6">Spring Boot</h3>
        <CodeBlock code={SPRING_REVOKE} language="java" title="TokenRevoker in Spring Boot" />

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2 mt-6">
          {language === 'en' ? 'Error handling' : 'Gestion des erreurs'}
        </h3>
        <CodeBlock code={ERROR_HANDLING} language="java" title="revoke() — error handling" />
      </section>
    </div>
  );
}
