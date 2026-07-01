import { useApp } from '../../context/AppContext';
import { Admonition } from '../../components/Admonition';
import { CodeBlock } from '../../components/CodeBlock';

const METRICS_BRIDGE_CODE = `// Bridge Veridot's LongAdder metrics to Spring Boot Micrometer
@Configuration
public class VeridotMetricsConfig {

    @Autowired
    public void registerVeridotMetrics(MeterRegistry registry) {
        FunctionCounter.builder("veridot.envelopes.accepted", 
                VeridotMetrics.ENVELOPE_ACCEPTED, LongAdder::doubleValue)
            .description("Total number of accepted envelopes")
            .register(registry);

        FunctionCounter.builder("veridot.envelopes.rejected", 
                VeridotMetrics.ENVELOPE_REJECTED, LongAdder::doubleValue)
            .description("Total number of rejected envelopes (failures)")
            .register(registry);

        FunctionCounter.builder("veridot.fence.contentions", 
                VeridotMetrics.FENCE_CONTENTIONS, LongAdder::doubleValue)
            .description("Total number of FENCE contentions detected")
            .register(registry);

        FunctionCounter.builder("veridot.reconciliations", 
                VeridotMetrics.RECONCILIATIONS, LongAdder::doubleValue)
            .description("Total number of database cache reconciliations performed")
            .register(registry);
    }
}`;

export function MetricsPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">Observability</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">Metrics Reference</h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Veridot core is designed with zero external dependencies and tracks security and operational metrics using standard JVM LongAdder counters.'
            : 'Le cœur de Veridot est conçu sans dépendance externe et suit les performances via des compteurs JVM LongAdder.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Core LongAdder Counters' : 'Compteurs LongAdder Cœur'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 mb-3 text-sm">
          {language === 'en'
            ? 'The VeridotMetrics class provides thread-safe static counters that can be inspected directly or bridged to micrometer/Prometheus:'
            : 'La classe VeridotMetrics expose des compteurs statiques sûrs pour les threads :' }
        </p>
        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700 mb-6">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800/50">
                {['Metric Field', 'Type', language === 'en' ? 'Description' : 'Description'].map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800 bg-white dark:bg-slate-900">
              {[
                { name: 'ENVELOPE_ACCEPTED', type: 'LongAdder', desc: language === 'en' ? 'Total number of successfully parsed and verified envelopes' : 'Nombre total d\'enveloppes analysées et vérifiées avec succès' },
                { name: 'ENVELOPE_REJECTED', type: 'LongAdder', desc: language === 'en' ? 'Total number of rejected tokens/envelopes (due to signature, expired key, revocation, etc.)' : 'Nombre total de rejets d\'enveloppes ou de jetons' },
                { name: 'FENCE_CONTENTIONS', type: 'LongAdder', desc: language === 'en' ? 'Number of failed concurrent operations due to stale fence version watermarks' : 'Nombre de collisions détectées sur les jetons de barrière (FENCE)' },
                { name: 'RECONCILIATIONS', type: 'LongAdder', desc: language === 'en' ? 'Number of full-scope cache database reconciliations successfully performed' : 'Nombre total de réconciliations du cache effectuées' },
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

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Prometheus & Micrometer Export' : 'Exportation Prometheus & Micrometer'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 mb-3 text-sm">
          {language === 'en'
            ? 'To expose these metrics to Prometheus, register them as FunctionCounters inside your Spring Boot application configuration:'
            : 'Pour exposer ces compteurs à Prometheus, enregistrez-les comme FunctionCounter dans la configuration de votre application Spring Boot :' }
        </p>
        <CodeBlock code={METRICS_BRIDGE_CODE} language="java" title="Bridging VeridotMetrics to Micrometer" />
      </section>

      <Admonition type="warning" title={language === 'en' ? 'Critical Alerts' : 'Alertes Critiques'}>
        {language === 'en'
          ? 'Set up alerts on veridot.envelopes.rejected. An increase in rejections indicates potential key compromises, expired token attempts, or configuration sync issues.'
          : 'Configurez des alertes sur veridot.envelopes.rejected. Une augmentation brutale des rejets signale des anomalies de signature ou des tentatives de rejeu.'}
      </Admonition>
    </div>
  );
}
