import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const RECORD_CODE = `public record VerifiedData<T>(
    String groupId,
    String sequenceId,
    T data
) {
    // Standard getters: groupId(), sequenceId(), data()
}`;

const USAGE_CODE = `// After calling TokenVerifier#verify:
VerifiedData<UserProfile> result = verifier.verify(token, 
    BasicConfigurer.deserializer(UserProfile.class));

// 1. Get the business payload
UserProfile profile = result.data();
System.out.println("User email: " + profile.email());

// 2. Get the session identifiers
String tenantId = result.groupId();       // e.g. "tenant-A"
String sessionId = result.sequenceId();   // e.g. "device-mobile-3"

// 3. Perform a session revocation using the extracted identifiers
revoker.revoke(tenantId, sessionId);`;

export function VerifiedDataPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">API Reference</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-1">VerifiedData</h1>
        <p className="text-sm font-mono text-slate-500 dark:text-slate-400 mb-4">io.github.cyfko.veridot.core</p>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'VerifiedData is a generic record that encapsulates the deserialized payload of a verified token, along with the V4 identifiers bound to the token at signing time.'
            : 'VerifiedData est un record générique qui encapsule la charge utile désérialisée d\'un token vérifié, ainsi que les identifiants V4 associés.'}
        </p>
      </div>

      <div className="rounded-xl border border-slate-200 dark:border-slate-700 overflow-hidden">
        <div className="px-5 py-3 bg-slate-50 dark:bg-slate-800/50 border-b border-slate-200 dark:border-slate-700">
          <span className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">
            {language === 'en' ? 'Record Definition' : 'Définition du Record'}
          </span>
        </div>
        <CodeBlock code={RECORD_CODE} language="java" showLineNumbers={false} />
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Common Usage' : 'Usage commun'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 mb-3">
          {language === 'en'
            ? 'Always extract the groupId and sequenceId to support fine-grained, session-level revocations in downstream services:'
            : 'Extrayez toujours le groupId et le sequenceId pour permettre des révocations ciblées dans les services aval :'}
        </p>
        <CodeBlock code={USAGE_CODE} language="java" title="VerifiedData usage example" />
      </section>

      <Admonition type="tip" title={language === 'en' ? 'Safe Deserialization' : 'Désérialisation Sécurisée'}>
        {language === 'en'
          ? 'Use BasicConfigurer.deserializer(Class) to safely convert the payload. Plain strings are passed through without modification.'
          : 'Utilisez BasicConfigurer.deserializer(Class) pour convertir de façon sûre. Les chaînes brutes sont renvoyées sans modification.'}
      </Admonition>
    </div>
  );
}
