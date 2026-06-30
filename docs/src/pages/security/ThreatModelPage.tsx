import { useApp } from '../../context/AppContext';
import { Admonition } from '../../components/Admonition';

export function ThreatModelPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">Security</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Threat Model' : 'Modèle de Menaces'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Detailed analysis of potential attack vectors on distributed authentication and how Veridot V4 mitigates them.'
            : 'Analyse détaillée des vecteurs d\'attaque potentiels sur l\'authentification distribuée et des parades de Veridot V4.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Threat Mitigation Matrix' : 'Matrice de Mitigations des Menaces'}
        </h2>
        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800/50">
                {['Threat Vector', language === 'en' ? 'Description' : 'Description', language === 'en' ? 'Mitigation' : 'Mitigation'].map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800 bg-white dark:bg-slate-900 text-slate-600 dark:text-slate-400">
              {[
                { threat: 'Broker Injection', desc: 'Attacker inserts fake key material into the Broker', mitigation: 'Mandatory TrustRoot verification on all retrieved envelopes' },
                { threat: 'State Rollback', desc: 'Attacker overwrites broker state with older valid entries', mitigation: 'Monotonic Version Invariant tracks local watermarks' },
                { threat: 'Unauthorized Config', desc: 'Attacker modifies active session limits', mitigation: 'CAPABILITY checks required for all CONFIG writes' },
                { threat: 'Replay Attack', desc: 'Attacker intercepts and replays active session entries', mitigation: 'Liveness validation with strict validUntil validity bounds' },
              ].map(row => (
                <tr key={row.threat}>
                  <td className="px-4 py-3 font-semibold text-slate-900 dark:text-white">{row.threat}</td>
                  <td className="px-4 py-3">{row.desc}</td>
                  <td className="px-4 py-3 text-emerald-600 dark:text-emerald-400 font-medium">{row.mitigation}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <Admonition type="security" title={language === 'en' ? 'Residual Risks' : 'Risques Résiduels'}>
        {language === 'en'
          ? 'Veridot protects the integrity and ordering of distributed states. It does not protect against physical host compromise or key custody leaks. Protect your long-term private keys in KMS/HSMs.'
          : 'Veridot protège la validité des états distribués mais ne protège pas contre la fuite physique des clés privées. Stockez vos clés dans un HSM/KMS.'}
      </Admonition>
    </div>
  );
}
