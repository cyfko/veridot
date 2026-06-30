import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const LOG_FORMAT = `[WARNING] Verifier rejection: error=V4201, entryId=(group:user-alice, KEY_EPOCH, mobile-app-v2)
[SEVERE] Trust resolution failed: issuer=admin-signer, error=V4101`;

export function LoggingPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">Observability</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">Logging & Audit Trails</h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Verify token rejections and inspect operational issues through standard Java Util Logging (JUL) outputs.'
            : 'Auditez les échecs et diagnostiquez les anomalies à l\'aide des logs Veridot standard.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Log Format & Categories' : 'Format des Logs & Catégories'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 mb-3">
          {language === 'en'
            ? 'Veridot logs rejections with the specific error code, target EntryId, and contextual issuer information:'
            : 'Veridot consigne les rejets en indiquant le code d\'erreur, l\'EntryId cible, et l\'émetteur concerné :'}
        </p>
        <CodeBlock code={LOG_FORMAT} language="text" showLineNumbers={false} />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'LogLevel Guidelines' : 'Directives de Niveau de Log'}
        </h2>
        <ul className="list-disc pl-5 space-y-2 text-sm text-slate-600 dark:text-slate-400">
          <li><strong>INFO</strong>: General lifecycle events (key rotation generation, configuration loads, starting background liveness loops).</li>
          <li><strong>WARNING</strong>: Token validations that fail due to client concerns (expired keys V4203, revoked sessions V4202, stale version updates V4201). These are expected during normal token lifecycles and logout events.</li>
          <li><strong>SEVERE</strong>: Cryptographic validation failures (V4101 signature failures, missing capabilities V4102, or transport failures V4401). These require immediate operator investigation.</li>
        </ul>
      </section>

      <Admonition type="info" title={language === 'en' ? 'Audit Logging' : 'Logs d\'Audit'}>
        {language === 'en'
          ? 'Every state change (key publication, revocation, configuration change) carries a unique monotonically increasing version and a signed issuer field, providing non-repudiation. Log outputs record these fields to facilitate post-incident forensics.'
          : 'Chaque changement d\'état est signé et numéroté de façon monotone, assurant la non-répudiation pour les audits.'}
      </Admonition>
    </div>
  );
}
