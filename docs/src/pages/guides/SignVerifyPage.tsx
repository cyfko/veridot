import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const SIGN_CODE = `// Configure basic signing parameters
BasicConfigurer config = BasicConfigurer.builder()
    .groupId("billing-tenant-3")
    .sequenceId("session-invoice-102")
    .validity(7200) // 2 hours validity
    .build();

// Sign and publish verification metadata to the broker
String token = signer.sign("invoice-payload-data", config);`;

const VERIFY_CODE = `// Verifier node receives the token string
try {
    VerifiedData<String> verified = verifier.verify(token, s -> s);
    System.out.println("Valid invoice verification: " + verified.data());
} catch (BrokerExtractionException e) {
    System.err.println("Verification rejected. Reason code: " + e.getErrorCode());
}`;

export function SignVerifyPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">
          {language === 'en' ? 'Guides · Basic Operations' : 'Guides · Opérations de Base'}
        </p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Sign & Verify Guide' : 'Guide Signer & Vérifier'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'A problem-solution walk-through for issuing and validating cryptographically secure tokens in microservices.'
            : 'Un guide problème-solution pour émettre et valider des tokens sécurisés dans vos microservices.'}
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
              ? 'Verifying signatures in microservices usually requires sharing a database of secrets (creating a major attack surface) or calling a central identity provider (IdP) on every request, which introduces latency and makes the IdP a runtime SPoF.'
              : 'La vérification classique de signatures nécessite soit de partager des secrets symétriques (risque de fuite), soit d\'appeler un IdP centralisé sur le chemin critique de chaque requête, créant de la latence et un point unique de défaillance.'}
          </p>
        </div>

        <div className="border border-emerald-200 dark:border-emerald-900 bg-emerald-50/50 dark:bg-emerald-950/10 rounded-xl p-5">
          <h3 className="text-emerald-700 dark:text-emerald-400 font-bold text-base mb-2">
            {language === 'en' ? 'The Solution' : 'La Solution'}
          </h3>
          <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
            {language === 'en'
              ? 'Veridot signers generate ephemeral asymmetric key pairs locally. Verifiers resolve trust through a public TrustRoot and validate signatures locally in <1ms from memory, eliminating both shared secrets and synchronous network calls.'
              : 'Les émetteurs Veridot génèrent des paires de clés asymétriques éphémères en local. Les vérificateurs résolvent la confiance via un TrustRoot public et valident la signature en local en <1ms depuis leur mémoire.'}
          </p>
        </div>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? '1. Issuing a Token (Signer Node)' : '1. Émission d\'un Token (Nœud Émetteur)'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 mb-3 text-sm">
          {language === 'en'
            ? 'Configure the payload identifiers (groupId, sequenceId) and call sign(). The issuer automatically signs the token and publishes metadata to the Broker:'
            : 'Configurez les identifiants de la charge utile (groupId, sequenceId) et appelez sign(). L\'émetteur signe le token et publie les métadonnées sur le Broker :'}
        </p>
        <CodeBlock code={SIGN_CODE} language="java" title="Signer implementation" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? '2. Verifying a Token (Verifier Node)' : '2. Vérification d\'un Token (Nœud Vérificateur)'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 mb-3 text-sm">
          {language === 'en'
            ? 'The verifier fetches metadata asynchronously in the background. When verify() is called, it performs an in-memory check:'
            : 'Le vérificateur charge les métadonnées en arrière-plan. Lors de l\'appel verify(), la validation s\'effectue en mémoire en moins d\'1 milliseconde :'}
        </p>
        <CodeBlock code={VERIFY_CODE} language="java" title="Verifier implementation" />
      </section>

      <Admonition type="tip" title={language === 'en' ? 'Proactive Defenses' : 'Défenses Proactives'}>
        {language === 'en'
          ? 'Always handle BrokerExtractionException to catch signature validation failures, expired key epochs, and explicit revocations.'
          : 'Gérez toujours l\'exception BrokerExtractionException pour intercepter les échecs de signature, les clés expirées et les révocations.'}
      </Admonition>
    </div>
  );
}
