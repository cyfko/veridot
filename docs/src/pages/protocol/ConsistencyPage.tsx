import { useApp } from '../../context/AppContext';
import { Admonition } from '../../components/Admonition';

export function ConsistencyPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">Protocol V4</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'State Consistency' : 'Cohérence de l\'État'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Understand Veridots hybrid consistency model: eventually consistent metadata propagation paired with strictly ordered capacity management.'
            : 'Comprenez le modèle de cohérence de Veridot : propagation éventuellement cohérente et mutations ordonnées.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Version Monotonicity' : 'Monotonie des Versions'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 leading-relaxed mb-3">
          {language === 'en'
            ? 'Verifiers track version watermarks locally. When an envelope is retrieved, the verifier asserts that the version is strictly greater than the currently stored value. If not, the update is rejected as stale (V4201).'
            : 'Les vérificateurs suivent localement les filigranes de version. À la réception d\'une enveloppe, ils s\'assurent que la version est supérieure au filigrane, sinon l\'entrée est rejetée (V4201).'}
        </p>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Snapshot Reconciliation' : 'Réconciliation par Instantanés'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 leading-relaxed mb-3">
          {language === 'en'
            ? 'To ensure missed updates are corrected, verifiers periodically query a full scope snapshot from the Broker (default every 15 minutes). If reconciliation has not run successfully for over 60 minutes, the verifier fails closed.'
            : 'Pour corriger d\'éventuels messages perdus, les vérificateurs récupèrent périodiquement un instantané complet du scope (toutes les 15 minutes). En cas d\'échec de réconciliation pendant plus de 60 minutes, la validation est bloquée.'}
        </p>
      </section>

      <Admonition type="security" title={language === 'en' ? 'Persistent Watermarks' : 'Persistance des Filigranes'}>
        {language === 'en'
          ? 'Verifiers persist watermarks using WatermarkStore. Serialized watermarks are signed or HMAC-protected using keys derived from the long-term private key, preventing tamper and state rollback attacks.'
          : 'Les vérificateurs sauvegardent les filigranes via un WatermarkStore. La signature ou l\'HMAC protège les filigranes contre les altérations au redémarrage.'}
      </Admonition>
    </div>
  );
}
