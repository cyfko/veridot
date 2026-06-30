import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const CONFIG_CODE = `// Publish configuration limiting a group to 3 concurrent active sessions
// using FIFO eviction policy (oldest session is evicted on overflow)
sv.publishConfig(
    ConfigScope.LOCAL,
    "user-alice",
    3,                     // max active sessions
    EvictionPolicy.FIFO,   // eviction policy
    3600,                  // default TTL (1 hour)
    10                     // validity duration
);`;

export function SessionCapacityPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">
          {language === 'en' ? 'Guides · Quotas' : 'Guides · Quotas'}
        </p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Session Capacity & Eviction' : 'Capacité de Session & Éviction'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'A problem-solution guide to limiting concurrent active sessions per group across distributed nodes.'
            : 'Un guide problème-solution pour limiter le nombre de sessions actives concurrentes par groupe.'}
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
              ? 'Enforcing a strict limit on active logins per user across multiple distinct microservices is prone to race conditions, requiring complex lock configurations in relational databases or shared Redis clusters.'
              : 'Appliquer une limite sur les connexions actives d\'un utilisateur dans un cluster distribué est sujet aux conditions de concurrence (race conditions) et nécessite généralement des verrous SQL complexes.'}
          </p>
        </div>

        <div className="border border-emerald-200 dark:border-emerald-900 bg-emerald-50/50 dark:bg-emerald-950/10 rounded-xl p-5">
          <h3 className="text-emerald-700 dark:text-emerald-400 font-bold text-base mb-2">
            {language === 'en' ? 'The Solution' : 'La Solution'}
          </h3>
          <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
            {language === 'en'
              ? 'Veridot coordinates quotas using dynamic CONFIG scopes and sequential FENCE tokens. Signers fetch the current active counts from local caches, apply the eviction policy (FIFO, LRU), and write updates atomically.'
              : 'Veridot utilise des règles CONFIG et des jetons FENCE de barrière de séquence. L\'émetteur consulte le nombre de sessions actives dans son cache, applique l\'éviction (FIFO/LRU), et écrit les modifications de façon atomique.'}
          </p>
        </div>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Enforcing Limits' : 'Application des limites'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 mb-3 text-sm">
          {language === 'en'
            ? 'Publish a CONFIG envelope defining the maximum session limit and eviction policy:'
            : 'Publiez une enveloppe CONFIG spécifiant la limite et la politique d\'éviction :'}
        </p>
        <CodeBlock code={CONFIG_CODE} language="java" title="Configuring session quota limits" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Eviction Policies' : 'Politiques d\'Éviction'}
        </h2>
        <ul className="list-disc pl-5 space-y-2 text-sm text-slate-600 dark:text-slate-400">
          <li><strong>FIFO</strong> (First-In-First-Out): Revokes the session with the oldest liveness registration timestamp.</li>
          <li><strong>LIFO</strong> (Last-In-First-Out): Revokes the most recently created session.</li>
          <li><strong>LRU</strong> (Least Recently Used): Revokes the session that has been verified least recently.</li>
          <li><strong>REJECT</strong>: Rejects the new login attempt and throws `SessionCapacityExceededException`.</li>
        </ul>
      </section>
    </div>
  );
}
