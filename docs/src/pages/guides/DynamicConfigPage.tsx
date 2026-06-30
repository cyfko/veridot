import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const PUBLISH_CODE = `// Publish a site-level configuration limiting session quota
// to 10 active tokens with LRU eviction policy
sv.publishConfig(
    ConfigScope.SITE,
    "emea-partner-site",  // siteId
    10,                   // maxSessions
    EvictionPolicy.LRU,   // eviction policy
    3600,                 // default key TTL (1 hour)
    60                    // validity duration (60 seconds)
);`;

export function DynamicConfigPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">
          {language === 'en' ? 'Guides · Configuration' : 'Guides · Configuration'}
        </p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Dynamic Configuration' : 'Configuration Dynamique'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'A problem-solution guide to implementing dynamic, hierarchical config policies across a cluster.'
            : 'Un guide problème-solution pour mettre en œuvre des politiques de configuration dynamiques.'}
        </p>
      </div>

      {/* Problem-Solution section */}
      <div className="grid sm:grid-cols-2 gap-4 my-6">
        <div className="border border-red-200 dark:border-red-900 bg-red-50/50 dark:bg-red-950/10 rounded-xl p-5">
          <h3 className="text-red-700 dark:text-red-400 font-bold text-base mb-2">
            {language === 'en' ? 'The Problem' : 'Le Problème'}
          </h3>
          <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
            {language === 'en'
              ? 'Changing security bounds (like session limits or eviction rules) usually requires modifying database columns, updating properties files, or restarting microservice pods, causing temporary service interruptions.'
              : 'Modifier les limites de sécurité (quotas de session, éviction) requiert habituellement de mettre à jour des tables SQL, éditer des fichiers properties ou redémarrer les pods, entraînant des coupures de service.'}
          </p>
        </div>

        <div className="border border-emerald-200 dark:border-emerald-900 bg-emerald-50/50 dark:bg-emerald-950/10 rounded-xl p-5">
          <h3 className="text-emerald-700 dark:text-emerald-400 font-bold text-base mb-2">
            {language === 'en' ? 'The Solution' : 'La Solution'}
          </h3>
          <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
            {language === 'en'
              ? 'Veridot distributes configs dynamically through signed CONFIG entries. Verification nodes resolve configuration properties in memory using a hierarchy (Group > Site > Global), reacting to updates instantly without reboots.'
              : 'Veridot distribue les configurations de façon dynamique via des enveloppes CONFIG signées. Les vérificateurs résolvent les règles en mémoire selon une hiérarchie (Groupe > Site > Global), appliquant les mises à jour sans redémarrage.'}
          </p>
        </div>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Publishing Config' : 'Publication de Configuration'}
        </h2>
        <CodeBlock code={PUBLISH_CODE} language="java" title="Publishing configuration" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Precedence Rules' : 'Règles de Priorité'}
        </h2>
        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800/50">
                {['Scope', 'Precedence', language === 'en' ? 'Applies to' : 'S\'applique à'].map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800 bg-white dark:bg-slate-900">
              <tr>
                <td className="px-4 py-3 font-mono font-medium">group:&lt;groupId&gt;</td>
                <td className="px-4 py-3 text-violet-600 font-semibold">1 (Highest)</td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{language === 'en' ? 'The specific group only' : 'Le groupe spécifique uniquement'}</td>
              </tr>
              <tr>
                <td className="px-4 py-3 font-mono font-medium">site:&lt;siteId&gt;</td>
                <td className="px-4 py-3 text-violet-600 font-semibold">2</td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{language === 'en' ? 'All groups declaring membership in this site' : 'Tous les groupes appartenant à ce site'}</td>
              </tr>
              <tr>
                <td className="px-4 py-3 font-mono font-medium">global</td>
                <td className="px-4 py-3 text-violet-600 font-semibold">3 (Lowest)</td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{language === 'en' ? 'All groups across the system' : 'Tous les groupes du système'}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}
