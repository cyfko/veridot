import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const DIRECT_CODE = `// DIRECT mode: returns the fully signed JWT token string
String token = signer.sign(userClaims,
    BasicConfigurer.builder()
        .groupId("user-102")
        .distribution(DistributionMode.DIRECT) // default
        .validity(3600)
        .build()
);`;

const INDIRECT_CODE = `// INDIRECT mode: returns the Protocol V4 messageId
String messageId = signer.sign(userClaims,
    BasicConfigurer.builder()
        .groupId("user-102")
        .distribution(DistributionMode.INDIRECT)
        .validity(3600)
        .build()
);`;

export function DistributionModesPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">
          {language === 'en' ? 'Guides · Bandwidth' : 'Guides · Bande passante'}
        </p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Token Distribution Modes' : 'Modes de Distribution des Tokens'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'A problem-solution guide to selecting between DIRECT and INDIRECT token distribution modes.'
            : 'Un guide problème-solution pour choisir entre le mode de distribution DIRECT et INDIRECT.'}
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
              ? 'Large token payloads (containing extensive roles, tenant properties, and profiles) consume significant network bandwidth on client devices, resulting in slow mobile loads and increased network costs.'
              : 'Les charges utiles de token volumineuses (contenant des rôles et profils détaillés) consomment une quantité importante de bande passante, ce qui ralentit les connexions mobiles et augmente le trafic.'}
          </p>
        </div>

        <div className="border border-emerald-200 dark:border-emerald-900 bg-emerald-50/50 dark:bg-emerald-950/10 rounded-xl p-5">
          <h3 className="text-emerald-700 dark:text-emerald-400 font-bold text-base mb-2">
            {language === 'en' ? 'The Solution' : 'La Solution'}
          </h3>
          <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
            {language === 'en'
              ? 'Veridot supports INDIRECT mode. Instead of sending the full token, the client receives a lightweight messageId. When verifying, the verifier microservice fetches the full token payload from the broker metadata, caching it locally.'
              : 'Veridot propose le mode INDIRECT. Au lieu d\'envoyer le token complet, le client reçoit un messageId léger. Le vérificateur se charge de récupérer le jeton sur le courtier, puis le stocke dans son cache local.'}
          </p>
        </div>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? '1. Direct Mode (Default)' : '1. Mode Direct (Par défaut)'}
        </h2>
        <CodeBlock code={DIRECT_CODE} language="java" title="Direct Mode setup" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? '2. Indirect Mode' : '2. Mode Indirect'}
        </h2>
        <CodeBlock code={INDIRECT_CODE} language="java" title="Indirect Mode setup" />
      </section>
    </div>
  );
}
