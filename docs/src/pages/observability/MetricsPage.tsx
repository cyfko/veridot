import { useApp } from '../../context/AppContext';
import { Admonition } from '../../components/Admonition';

export function MetricsPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">Observability</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">Metrics Reference</h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Monitor Veridot performance and detect security anomalies using standard Prometheus metrics exposed by the verifier instances.'
            : 'Surveillez les performances et détectez les menaces à l\'aide des métriques Prometheus exposées par les vérificateurs.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Prometheus Metrics' : 'Métriques Prometheus'}
        </h2>
        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800/50">
                {['Metric Name', 'Type', language === 'en' ? 'Description' : 'Description'].map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800 bg-white dark:bg-slate-900">
              {[
                { name: 'veridot_rejections_total', type: 'Counter', desc: language === 'en' ? 'Total number of rejected tokens, labeled by error (V4201, V4202, etc.)' : 'Nombre total de rejets de tokens, étiqueté par code d\'erreur' },
                { name: 'veridot_watermark_staleness_ms', type: 'Gauge', desc: language === 'en' ? 'Current delay in milliseconds since the last successful reconciliation' : 'Délai actuel depuis la dernière réconciliation du cache' },
                { name: 'veridot_fence_contention_total', type: 'Counter', desc: language === 'en' ? 'Number of failed concurrent quota mutations due to stale fence tokens' : 'Nombre de collisions de jetons de barrière (FENCE) lors de mutations concurrentes' },
                { name: 'veridot_active_sessions', type: 'Gauge', desc: language === 'en' ? 'Current count of active sessions for a monitored user group' : 'Nombre de sessions actives pour un groupe d\'utilisateurs surveillé' },
              ].map(row => (
                <tr key={row.name}>
                  <td className="px-4 py-3 font-mono font-medium text-violet-600">{row.name}</td>
                  <td className="px-4 py-3 text-slate-500 font-mono text-xs">{row.type}</td>
                  <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{row.desc}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <Admonition type="warning" title={language === 'en' ? 'Critical Alerts' : 'Alertes Critiques'}>
        {language === 'en'
          ? 'Set up alerts on veridot_rejections_total for codes V4101 (Trust failure) and V4201 (Stale version). An increase in these codes indicates potential key compromises or configuration sync issues.'
          : 'Configurez des alertes sur veridot_rejections_total pour les codes V4101 (échec signature) et V4201 (version obsolète) afin de repérer les attaques de rejeu.'}
      </Admonition>
    </div>
  );
}
