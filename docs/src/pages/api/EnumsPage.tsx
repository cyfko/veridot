import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';

const ALGORITHM_CODE = `public enum Algorithm {
    RSA_SHA256  ((byte) 0x01, "SHA256withRSA",  "RSA"),
    ECDSA_SHA256((byte) 0x02, "SHA256withECDSA", "EC"),
    RSA_PSS     ((byte) 0x03, "RSASSA-PSS",      "RSA"),
    ED25519     ((byte) 0x04, "Ed25519",          "Ed25519");

    public byte getCode()             // Protocol V4 byte code
    public String getJcaSignatureAlg() // JCA algorithm name
    public String getJcaKeyAlg()      // JCA key algorithm name

    public static Algorithm fromCode(byte code)
}`;

const EVICTION_CODE = `public enum EvictionPolicy {
    FIFO,    // Evict the session with the earliest LIVENESS(ACTIVE).asOf
    LIFO,    // Evict the session with the most recent LIVENESS(ACTIVE).asOf
    LRU,     // Evict least recently used (by asOf — equivalent to FIFO in current impl)
    REJECT   // Refuse the signing attempt; throw SessionCapacityExceededException
}`;

const DISTRIBUTION_CODE = `public enum DistributionMode {
    DIRECT,   // Return the signed JWT directly. Default mode.
    INDIRECT  // Store JWT in broker; return messageId ("4:<groupId>:<sequenceId>")
}`;

const CONFIG_SCOPE_CODE = `public enum ConfigScope {
    LOCAL,   // Applies to a specific groupId
    SITE,    // Applies to all groups declaring a specific siteId
    GLOBAL   // Applies to all groups on the broker
}`;

const VERIFIED_DATA_CODE = `public record VerifiedData<T>(
    String groupId,    // Protocol V4 group identifier
    String sequenceId, // Protocol V4 session identifier within the group
    T data             // Deserialized payload
) {}

// Usage:
VerifiedData<UserClaims> result = verifier.verify(token,
    json -> mapper.readValue(json, UserClaims.class));

String group   = result.groupId();    // "user-alice"
String session = result.sequenceId(); // "mobile-v2"
UserClaims u   = result.data();       // your typed payload`;

export function EnumsPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">API Reference</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Enums & Records' : 'Enums & Records'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Complete reference for all enumerations and record types in the Veridot public API.'
            : 'Référence complète pour toutes les énumérations et types record dans l\'API publique Veridot.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-2">Algorithm</h2>
        <p className="text-sm font-mono text-slate-500 dark:text-slate-400 mb-4">io.github.cyfko.veridot.core</p>
        <p className="text-slate-600 dark:text-slate-400 mb-3 text-sm">
          {language === 'en'
            ? 'Registry of supported cryptographic algorithms. Unifies Protocol V4 byte codes, JCA signature algorithm names, and key algorithm names.'
            : 'Registre des algorithmes cryptographiques supportés. Unifie les codes octets du Protocole V4, les noms d\'algorithmes de signature JCA et les noms d\'algorithmes de clé.'}
        </p>
        <CodeBlock code={ALGORITHM_CODE} language="java" title="Algorithm enum" />
        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700 mt-3">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800/50">
                {(language === 'en' ? ['Value', 'Protocol Code', 'JCA Signature', 'JCA Key', 'Recommendation'] : ['Valeur', 'Code Protocole', 'Signature JCA', 'Clé JCA', 'Recommandation']).map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800 bg-white dark:bg-slate-900">
              {[
                { val: 'RSA_SHA256', code: '0x01', sig: 'SHA256withRSA', key: 'RSA', rec: language === 'en' ? 'Legacy — use ED25519 for new' : 'Héritage — utiliser ED25519' },
                { val: 'ECDSA_SHA256', code: '0x02', sig: 'SHA256withECDSA', key: 'EC', rec: language === 'en' ? 'Good — timing-safe with Ed25519' : 'Bien — timing-safe avec Ed25519' },
                { val: 'RSA_PSS', code: '0x03', sig: 'RSASSA-PSS', key: 'RSA', rec: language === 'en' ? 'Preferred over RSA_SHA256' : 'Préféré à RSA_SHA256' },
                { val: 'ED25519', code: '0x04', sig: 'Ed25519', key: 'Ed25519', rec: language === 'en' ? '★ Recommended (NIST SP 800-186)' : '★ Recommandé (NIST SP 800-186)' },
              ].map(row => (
                <tr key={row.val} className={row.val === 'ED25519' ? 'bg-violet-50 dark:bg-violet-950/20' : ''}>
                  <td className="px-4 py-3 font-mono text-violet-700 dark:text-violet-300">{row.val}</td>
                  <td className="px-4 py-3 font-mono text-sm text-slate-600 dark:text-slate-400">{row.code}</td>
                  <td className="px-4 py-3 font-mono text-xs text-slate-500">{row.sig}</td>
                  <td className="px-4 py-3 font-mono text-xs text-slate-500">{row.key}</td>
                  <td className="px-4 py-3 text-sm text-slate-600 dark:text-slate-400">{row.rec}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-2">EvictionPolicy</h2>
        <p className="text-sm font-mono text-slate-500 dark:text-slate-400 mb-4">io.github.cyfko.veridot.core</p>
        <p className="text-slate-600 dark:text-slate-400 mb-3 text-sm">
          {language === 'en'
            ? 'Strategy applied when a new signing attempt would exceed the maxSessions limit for a group.'
            : 'Stratégie appliquée quand une nouvelle tentative de signature dépasserait la limite maxSessions pour un groupe.'}
        </p>
        <CodeBlock code={EVICTION_CODE} language="java" title="EvictionPolicy enum" />
        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700 mt-3">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800/50">
                {(language === 'en' ? ['Value', 'Behavior when maxSessions reached', 'Protocol V4 pol code'] : ['Valeur', 'Comportement quand maxSessions atteint', 'Code pol Protocole V4']).map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800 bg-white dark:bg-slate-900">
              {[
                { val: 'FIFO', behav: language === 'en' ? 'Evict session with oldest LIVENESS(ACTIVE).asOf' : 'Évincer la session avec le plus ancien LIVENESS(ACTIVE).asOf', code: '0x01' },
                { val: 'LIFO', behav: language === 'en' ? 'Evict session with most recent LIVENESS(ACTIVE).asOf' : 'Évincer la session avec le plus récent LIVENESS(ACTIVE).asOf', code: '0x02' },
                { val: 'LRU', behav: language === 'en' ? 'Evict least recently used (by asOf — currently equivalent to FIFO)' : 'Évincer le moins récemment utilisé (par asOf — actuellement équivalent à FIFO)', code: '0x03' },
                { val: 'REJECT', behav: language === 'en' ? 'Refuse sign(); throw SessionCapacityExceededException' : 'Refuser sign() ; lancer SessionCapacityExceededException', code: '0x04' },
              ].map(row => (
                <tr key={row.val}>
                  <td className="px-4 py-3 font-mono text-violet-700 dark:text-violet-300">{row.val}</td>
                  <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{row.behav}</td>
                  <td className="px-4 py-3 font-mono text-xs text-slate-500">{row.code}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-2">DistributionMode</h2>
        <p className="text-sm font-mono text-slate-500 dark:text-slate-400 mb-4">io.github.cyfko.veridot.core</p>
        <p className="text-slate-600 dark:text-slate-400 mb-3 text-sm">
          {language === 'en'
            ? 'Determines how the signed token is delivered after sign().'
            : 'Détermine comment le token signé est livré après sign().'}
        </p>
        <CodeBlock code={DISTRIBUTION_CODE} language="java" title="DistributionMode enum" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-2">ConfigScope</h2>
        <p className="text-sm font-mono text-slate-500 dark:text-slate-400 mb-4">io.github.cyfko.veridot.core</p>
        <p className="text-slate-600 dark:text-slate-400 mb-3 text-sm">
          {language === 'en'
            ? 'Target scope for publishConfig(). Determines which groups the configuration applies to.'
            : 'Scope cible pour publishConfig(). Détermine à quels groupes la configuration s\'applique.'}
        </p>
        <CodeBlock code={CONFIG_SCOPE_CODE} language="java" title="ConfigScope enum" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-2">VerifiedData&lt;T&gt;</h2>
        <p className="text-sm font-mono text-slate-500 dark:text-slate-400 mb-4">io.github.cyfko.veridot.core</p>
        <p className="text-slate-600 dark:text-slate-400 mb-3 text-sm">
          {language === 'en'
            ? 'Immutable record returned by verify(). Carries the deserialized payload and Protocol V4 identifiers.'
            : 'Record immuable retourné par verify(). Porte le payload désérialisé et les identifiants du Protocole V4.'}
        </p>
        <CodeBlock code={VERIFIED_DATA_CODE} language="java" title="VerifiedData<T> record" />
      </section>
    </div>
  );
}
