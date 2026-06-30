import { useApp } from '../../context/AppContext';
import { Admonition } from '../../components/Admonition';

export function TroubleshootingPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">Reference</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Troubleshooting Guide' : 'Guide de Dépannage'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Identify and resolve common issues encountered during Veridot integration or cluster runtime.'
            : 'Identifiez et résolvez les erreurs courantes rencontrées lors de l\'intégration de Veridot.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Handling Version Rollover Errors (V4201)' : 'Résolution des Erreurs de Version (V4201)'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 text-sm leading-relaxed mb-3">
          {language === 'en'
            ? 'V4201 (STALE_VERSION) is thrown when a verifier receives an envelope whose version is less than or equal to its local watermark. If you see this after a re-signing event, make sure the version numbers of your new envelopes are strictly greater than the last versions accepted by verifier nodes.'
            : 'Le code V4201 est levé quand un vérificateur reçoit une version inférieure ou égale au filigrane local. Assurez-vous d\'incrémenter les versions lors des ré-émissions.'}
        </p>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Solving Cache Expirations (RECONCILIATION_STALE)' : 'Résolution des Expirations de Cache (RECONCILIATION_STALE)'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 text-sm leading-relaxed mb-3">
          {language === 'en'
            ? 'If a verifier cannot run its periodic reconciliation snapshot scan for over 60 minutes, it raises RECONCILIATION_STALE and blocks validation. Verify network links and database replica availability, ensuring the Broker is reachable.'
            : 'Si le cache ne se synchronise pas avec le Broker pendant plus de 60 minutes, il lève RECONCILIATION_STALE. Vérifiez l\'état du courtier.'}
        </p>
      </section>

      <Admonition type="warning" title={language === 'en' ? 'Clock Synchronization' : 'Synchronisation des Horloges'}>
        {language === 'en'
          ? 'Veridot allows a clock drift of 5 minutes. If servers are out of sync, tokens will be rejected with V4203 (Expired) or V4202 (Liveness not established). Enforce NTP synchronization across all cluster nodes.'
          : 'Veridot tolère une dérive d\'horloge de 5 minutes. Activez le protocole NTP pour synchroniser les serveurs.'}
      </Admonition>
    </div>
  );
}
