import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const SINGLE_REVOKE = `// Revoke a single specific session using its unique identifiers
// Useful for logging out of a single device
String groupId = "user-1002";
String sessionId = "session-tablet-45";
revoker.revoke(groupId, sessionId);`;

const GROUP_REVOKE = `// Revoke all sessions belonging to the group
// Useful for password changes or account suspension
String groupId = "user-1002";
revoker.revoke(groupId, null);`;

export function RevocationPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">
          {language === 'en' ? 'Guides · Revocation' : 'Guides · Révocation'}
        </p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Revocation Guide' : 'Guide de Révocation'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'A problem-solution walk-through for invalidating issued tokens instantly across distributed systems.'
            : 'Un guide problème-solution pour invalider instantanément des tokens émis à l\'échelle de votre cluster.'}
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
              ? 'Stateless JWTs cannot be revoked before their expiration time without introducing a central state database (such as Redis), which creates a single point of failure and makes the check stateful and slow.'
              : 'Les tokens JWT classiques ne peuvent pas être révoqués avant leur date d\'expiration sans ajouter une base de données centrale d\'exclusion (comme Redis), annulant la nature stateless du protocole et ralentissant la validation.'}
          </p>
        </div>

        <div className="border border-emerald-200 dark:border-emerald-900 bg-emerald-50/50 dark:bg-emerald-950/10 rounded-xl p-5">
          <h3 className="text-emerald-700 dark:text-emerald-400 font-bold text-base mb-2">
            {language === 'en' ? 'The Solution' : 'La Solution'}
          </h3>
          <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
            {language === 'en'
              ? 'Veridot introduces positive-proof liveness. Revoking a token publishes a signed LIVENESS(REVOKED) entry to the Broker. Downstream verifier nodes pull these events in the background, updating their local caches for <1ms checks.'
              : 'Veridot utilise des attestations de liveness. La révocation publie une enveloppe LIVENESS(REVOKED) sur le Broker. Les vérificateurs répliquent ces événements en arrière-plan dans leur cache local.'}
          </p>
        </div>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? '1. Session-level Revocation' : '1. Révocation de Session unique'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 mb-3 text-sm">
          {language === 'en'
            ? 'To log out a single device, specify both the groupId and the sequenceId:'
            : 'Pour déconnecter un seul appareil, spécifiez à la fois le groupId et le sequenceId :'}
        </p>
        <CodeBlock code={SINGLE_REVOKE} language="java" title="Single session revocation" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? '2. Group-level Revocation (Global Logout)' : '2. Révocation de Groupe (Déconnexion Globale)'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 mb-3 text-sm">
          {language === 'en'
            ? 'To invalidate all sessions of a user (e.g. after a password change or account compromise), pass null as the sequenceId:'
            : 'Pour invalider l\'ensemble des sessions d\'un utilisateur (ex: changement de mot de passe), passez le paramètre sequenceId à null :'}
        </p>
        <CodeBlock code={GROUP_REVOKE} language="java" title="Group-wide revocation" />
      </section>

      <Admonition type="info" title={language === 'en' ? 'Reconciliation Bounds' : 'Limites de Réconciliation'}>
        {language === 'en'
          ? 'Revocation takes effect across all instances as soon as the verifier consumer loop consumes the event. In case of network drops, the background reconciliation sync ensures complete catch-up.'
          : 'La révocation prend effet dès que la boucle de consommation du vérificateur intercepte le message. En cas de coupure, la réconciliation automatique prend le relais.'}
      </Admonition>
    </div>
  );
}
