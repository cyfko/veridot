import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

export function CryptographyPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">Security</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Cryptographic Model' : 'Modèle Cryptographique'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Detailed specification of Veridots cryptographic algorithms, key hierarchies, and timing-attack protections.'
            : 'Spécification détaillée des algorithmes, hiérarchies de clés et protections contre les canaux auxiliaires.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Supported Asymmetric Signature Algorithms' : 'Algorithmes Asymétriques Supportés'}
        </h2>
        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800/50">
                {['Code', 'Algorithm', language === 'en' ? 'Description' : 'Description'].map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800 bg-white dark:bg-slate-900">
              {[
                { code: '0x01', name: 'RSA-SHA256', desc: 'RSASSA-PKCS1-v1_5 signature scheme (RFC 8017)' },
                { code: '0x02', name: 'ECDSA-SHA256', desc: 'ECDSA using P-256 elliptic curve (FIPS 186-5)' },
                { code: '0x03', name: 'RSA-PSS', desc: 'Probabilistic Signature Scheme (NIST SP 800-56B)' },
                { code: '0x04', name: 'Ed25519', desc: 'Edwards-Curve Digital Signature Algorithm (RFC 8032)' },
              ].map(row => (
                <tr key={row.code}>
                  <td className="px-4 py-3 font-mono text-violet-600 font-semibold">{row.code}</td>
                  <td className="px-4 py-3 font-mono font-medium">{row.name}</td>
                  <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{row.desc}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <Admonition type="tip" title={language === 'en' ? 'Ed25519 Recommendation' : 'Recommandation Ed25519'}>
        {language === 'en'
          ? 'Use Ed25519 (0x04) for all keys. Its mathematical structure is inherently resistant to timing and cache-based side-channel attacks, and provides compact signature sizes.'
          : 'Utilisez Ed25519 (0x04) pour toutes vos clés. Sa structure offre une protection native contre les attaques de timing de cache CPU.'}
      </Admonition>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Algorithm Confusion Protection' : 'Protection contre la Confusion d\'Algorithme'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 leading-relaxed mb-3">
          {language === 'en'
            ? 'Attackers sometimes attempt to present asymmetric public keys as symmetric HMAC keys. Veridot explicitly blocks this by verifying that the signature algorithm resolved from the TrustRoot matches the exact type defined in the envelope header. Furthermore, the JWT header alg attribute is checked against the key algorithm in the KEY_EPOCH envelope (e.g. RS256, ES256, PS256, or EdDSA).'
            : 'Veridot empêche la confusion d\'algorithme en vérifiant que le format de signature de l\'enveloppe correspond au type de clé du TrustRoot. Il vérifie également l\'algorithme dans l\'en-tête JWT.'}
        </p>
      </section>
    </div>
  );
}
