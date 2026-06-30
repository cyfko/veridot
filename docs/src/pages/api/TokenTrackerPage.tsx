import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const INTERFACE_CODE = `public interface TokenTracker {
    boolean hasActiveToken(Object target);
}`;

const BY_GROUP_ID = `TokenTracker tracker = new GenericSignerVerifier(broker, trust, "svc", key, Algorithm.ED25519);

// Check if a user has ANY active session
boolean isLoggedIn = tracker.hasActiveToken("user-alice");

// Use case: before issuing a new token, check if a session already exists
if (!tracker.hasActiveToken("user-alice")) {
    String token = signer.sign(email, config);
}`;

const BY_JWT = `// Check if a specific JWT token is still active (not expired, not revoked)
String token = "eyJhbGciOiJFZERTQSJ9...";
boolean stillValid = tracker.hasActiveToken(token);

// Use case: lightweight pre-check before full verify()
if (tracker.hasActiveToken(token)) {
    VerifiedData<String> result = verifier.verify(token, s -> s);
}`;

const BY_MESSAGE_ID = `// Check by Protocol V4 messageId (INDIRECT mode)
boolean active = tracker.hasActiveToken("4:reports:q2-2026");

// messageId format: "<protoVersion>:<groupId>:<sequenceId>"
// target resolution order:
// 1. starts with JWT header? → extract sub claim → check as messageId
// 2. starts with "<digit>:"? → treat as messageId → check LIVENESS directly
// 3. otherwise → treat as groupId → check if any ACTIVE session exists`;

const RESOLUTION_ORDER = `// Target resolution logic in GenericSignerVerifier:
//
// 1. If target is a JWT (3-part base64 string):
//    → extract sub claim → parse as messageId → check LIVENESS entry
//
// 2. If target starts with version prefix ("4:"):
//    → parse as messageId("<protoVersion>:<groupId>:<sequenceId>")
//    → check LIVENESS entry for (group:<groupId>, <sequenceId>)
//
// 3. Otherwise:
//    → treat as groupId
//    → count active sessions for scope "group:<target>"
//    → return true if count > 0
//
// Returns false for any error (never throws for invalid target format,
// except IllegalArgumentException if target is not a String)`;

export function TokenTrackerPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">API Reference</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-1">TokenTracker</h1>
        <p className="text-sm font-mono text-slate-500 dark:text-slate-400 mb-4">io.github.cyfko.veridot.core</p>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Interface for checking token activity without full verification. Useful for guarding resources before issuing a new token or performing a lightweight activity check.'
            : 'Interface pour vérifier l\'activité des tokens sans vérification complète. Utile pour protéger les ressources avant d\'émettre un nouveau token ou effectuer une vérification d\'activité légère.'}
        </p>
      </div>

      <div className="rounded-xl border border-slate-200 dark:border-slate-700 overflow-hidden">
        <div className="px-5 py-3 bg-slate-50 dark:bg-slate-800/50 border-b border-slate-200 dark:border-slate-700">
          <span className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">
            {language === 'en' ? 'Interface declaration' : 'Déclaration de l\'interface'}
          </span>
        </div>
        <CodeBlock code={INTERFACE_CODE} language="java" showLineNumbers={false} />
      </div>

      <Admonition type="info" title={language === 'en' ? 'Polymorphic target parameter' : 'Paramètre target polymorphique'}>
        {language === 'en'
          ? 'hasActiveToken() accepts three target formats: a groupId string (checks any active session in the group), a signed JWT (extracts messageId from sub claim), or a Protocol V4 messageId (format: "4:<groupId>:<sequenceId>"). The parameter is Object but must be a String — passing any other type throws IllegalArgumentException.'
          : 'hasActiveToken() accepte trois formats de cible : une chaîne groupId (vérifie toute session active dans le groupe), un JWT signé (extrait le messageId de la revendication sub), ou un messageId Protocole V4 (format : "4:<groupId>:<sequenceId>"). Le paramètre est Object mais doit être une String — passer tout autre type lance IllegalArgumentException.'}
      </Admonition>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Target resolution order' : 'Ordre de résolution de la cible'}
        </h2>
        <CodeBlock code={RESOLUTION_ORDER} language="java" title="hasActiveToken() — resolution logic" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Examples' : 'Exemples'}
        </h2>

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2">
          {language === 'en' ? 'By groupId' : 'Par groupId'}
        </h3>
        <CodeBlock code={BY_GROUP_ID} language="java" title="hasActiveToken() — by groupId" />

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2 mt-6">
          {language === 'en' ? 'By JWT token' : 'Par token JWT'}
        </h3>
        <CodeBlock code={BY_JWT} language="java" title="hasActiveToken() — by JWT" />

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2 mt-6">
          {language === 'en' ? 'By messageId (INDIRECT mode)' : 'Par messageId (mode INDIRECT)'}
        </h3>
        <CodeBlock code={BY_MESSAGE_ID} language="java" title="hasActiveToken() — by messageId" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'When to use TokenTracker vs TokenVerifier' : 'Quand utiliser TokenTracker vs TokenVerifier'}
        </h2>
        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800/50">
                {(language === 'en' ? ['Use case', 'TokenTracker', 'TokenVerifier'] : ['Cas d\'usage', 'TokenTracker', 'TokenVerifier']).map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800 bg-white dark:bg-slate-900">
              {(language === 'en' ? [
                { use: 'Check if user is logged in', tracker: '✓ Fast, no deserialization', verifier: 'Overkill' },
                { use: 'Authorize an API request', tracker: '✗ No payload extraction', verifier: '✓ Required' },
                { use: 'Check if session limit reached', tracker: '✓ Use hasActiveToken(groupId)', verifier: '✗ Wrong tool' },
                { use: 'Validate token before serving cached data', tracker: '✓ Pre-check', verifier: 'Full check if needed' },
                { use: 'Extract user claims (email, roles)', tracker: '✗ No payload', verifier: '✓ Required' },
              ] : [
                { use: 'Vérifier si l\'utilisateur est connecté', tracker: '✓ Rapide, pas de désérialisation', verifier: 'Surdimensionné' },
                { use: 'Autoriser une requête API', tracker: '✗ Pas d\'extraction de payload', verifier: '✓ Requis' },
                { use: 'Vérifier si la limite de session est atteinte', tracker: '✓ Utiliser hasActiveToken(groupId)', verifier: '✗ Mauvais outil' },
                { use: 'Extraire les revendications utilisateur (email, rôles)', tracker: '✗ Pas de payload', verifier: '✓ Requis' },
              ]).map((row, i) => (
                <tr key={i}>
                  <td className="px-4 py-3 text-slate-700 dark:text-slate-300">{row.use}</td>
                  <td className="px-4 py-3 text-sm">{row.tracker.startsWith('✓') ? <span className="text-emerald-600 dark:text-emerald-400">{row.tracker}</span> : row.tracker.startsWith('✗') ? <span className="text-red-400">{row.tracker}</span> : <span className="text-slate-500">{row.tracker}</span>}</td>
                  <td className="px-4 py-3 text-sm">{row.verifier.startsWith('✓') ? <span className="text-emerald-600 dark:text-emerald-400">{row.verifier}</span> : row.verifier.startsWith('✗') ? <span className="text-red-400">{row.verifier}</span> : <span className="text-slate-500">{row.verifier}</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}
