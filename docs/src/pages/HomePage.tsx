import { Link } from 'react-router-dom';
import { Shield, Zap, RefreshCw, Lock, ArrowRight, CheckCircle, Package, Code2 } from 'lucide-react';
import { useApp } from '../context/AppContext';
import { CodeBlock } from '../components/CodeBlock';

const QUICK_START_CODE = `// 1. Build the signer/verifier
var sv = new GenericSignerVerifier(
    broker,       // KafkaBroker or DatabaseBroker
    trustRoot,    // your TrustRoot implementation
    "auth-service",
    longTermPrivateKey,
    Algorithm.ED25519
);

// 2. Sign — any service in your cluster
String token = sv.sign("alice@example.com",
    BasicConfigurer.builder()
        .groupId("user-alice")
        .sequenceId("mobile-v2")
        .validity(3600)          // 1 hour
        .build());

// 3. Verify — from ANY other service, <1ms
VerifiedData<String> result = sv.verify(token, s -> s);
System.out.println(result.data());       // "alice@example.com"
System.out.println(result.groupId());    // "user-alice"

// 4. Revoke — instantly across the cluster
sv.revoke("user-alice", "mobile-v2");   // one session
sv.revoke("user-alice", null);           // all sessions`;

const content = {
  en: {
    badge: 'Protocol V4.0 · Java 25+',
    headline: 'Secure distributed state. Sub-millisecond verification.',
    headline2: 'Zero shared secrets. Instant revocation.',
    description: 'Veridot is a high-performance Java library for distributed state attestation and capability delegation. Sign, verify, and revoke tokens or credentials locally in under 1ms—ideal for API authentication, machine-to-machine security, and one-time action verification.',
    getStarted: 'Get Started',
    apiRef: 'API Reference',
    why: 'Why Veridot?',
    whyDesc: 'Every distributed authentication and coordination model forces a compromise. Veridot eliminates all three.',
    quickStart: 'Four lines to production-grade security',
    quickStartDesc: 'Sign, verify, and revoke — the complete authentication and capability lifecycle in a few lines of Java.',
    features: 'Designed for Developer Experience',
    learnMore: 'Learn more',
    featuresList: [
      {
        icon: Zap,
        title: 'Sub-millisecond verification',
        desc: 'Verification reads directly from a local RocksDB memory cache—no database or broker call is required on the hot validation path.',
        color: 'text-amber-500',
        bg: 'bg-amber-50 dark:bg-amber-950/30',
      },
      {
        icon: Lock,
        title: 'Zero shared secrets',
        desc: 'Asymmetric ephemeral key pairs. Downstream nodes share only the broker address and a public TrustRoot — never private or symmetric keys.',
        color: 'text-violet-500',
        bg: 'bg-violet-50 dark:bg-violet-950/30',
      },
      {
        icon: RefreshCw,
        title: 'Instant revocation',
        desc: 'Publish a signed LIVENESS=REVOKED entry. All verifiers reject the session or capability within one reconciliation cycle.',
        color: 'text-emerald-500',
        bg: 'bg-emerald-50 dark:bg-emerald-950/30',
      },
      {
        icon: Shield,
        title: 'Broker-untrusted architecture',
        desc: 'The broker stores bytes but holds no authority. Even with full write access, an attacker cannot forge valid keys or configurations.',
        color: 'text-blue-500',
        bg: 'bg-blue-50 dark:bg-blue-950/30',
      },
    ],
    trilemma: {
      title: 'The distributed coordination trilemma',
      rows: [
        { approach: 'Shared HMAC', revocable: true, noSecret: false, noCall: true },
        { approach: 'Stateless RSA/ECDSA JWT', revocable: false, noSecret: true, noCall: true },
        { approach: 'Centralized IdP call', revocable: true, noSecret: true, noCall: false },
        { approach: 'Veridot', revocable: true, noSecret: true, noCall: true, highlight: true },
      ],
      cols: ['Approach', 'Revocable?', 'No shared secret?', 'No network call?'],
    },
  },
  fr: {
    badge: 'Protocole V4.0 · Java 25+',
    headline: 'Sécurisez l\'état distribué. Vérification sous-milliseconde.',
    headline2: 'Zéro secret partagé. Révocation instantanée.',
    description: 'Veridot est une bibliothèque Java haute performance pour l\'attestation d\'état distribué et la délégation de privilèges. Signez, vérifiez et révoquez localement en moins d\'1 ms — idéal pour l\'authentification API, la sécurité M2M et les jetons à usage unique.',
    getStarted: 'Démarrer',
    apiRef: 'Référence API',
    why: 'Pourquoi Veridot ?',
    whyDesc: 'Chaque approche d\'authentification et de coordination distribuée force un compromis. Veridot les élimine tous.',
    quickStart: 'Quatre lignes vers une sécurité de production',
    quickStartDesc: 'Signer, vérifier et révoquer — le cycle de vie complet de l\'autorisation en quelques lignes de Java.',
    features: 'Conçu pour la Developer Experience',
    learnMore: 'En savoir plus',
    featuresList: [
      {
        icon: Zap,
        title: 'Vérification sous-milliseconde',
        desc: 'Le cache RocksDB local élimine les allers-retours réseau. La vérification lit depuis la mémoire — aucun appel de base de données requis.',
        color: 'text-amber-500',
        bg: 'bg-amber-50 dark:bg-amber-950/30',
      },
      {
        icon: Lock,
        title: 'Zéro secret partagé',
        desc: 'Paires de clés asymétriques éphémères. Les services partagent uniquement l\'adresse du broker et une TrustRoot publique.',
        color: 'text-violet-500',
        bg: 'bg-violet-50 dark:bg-violet-950/30',
      },
      {
        icon: RefreshCw,
        title: 'Révocation instantanée',
        desc: 'Publiez une entrée LIVENESS=REVOKED signée. Tous les vérificateurs rejettent le token dans un cycle de réconciliation.',
        color: 'text-emerald-500',
        bg: 'bg-emerald-50 dark:bg-emerald-950/30',
      },
      {
        icon: Shield,
        title: 'Architecture broker non fiable',
        desc: 'Le broker stocke des octets mais ne détient aucune autorité. Même avec un accès complet en écriture, un attaquant ne peut pas forger une entrée valide.',
        color: 'text-blue-500',
        bg: 'bg-blue-50 dark:bg-blue-950/30',
      },
    ],
    trilemma: {
      title: 'Le trilemme de la coordination distribuée',
      rows: [
        { approach: 'HMAC partagé', revocable: true, noSecret: false, noCall: true },
        { approach: 'JWT RSA/ECDSA stateless', revocable: false, noSecret: true, noCall: true },
        { approach: 'Appel IdP centralisé', revocable: true, noSecret: true, noCall: false },
        { approach: 'Veridot', revocable: true, noSecret: true, noCall: true, highlight: true },
      ],
      cols: ['Approche', 'Révocable ?', 'Sans secret partagé ?', 'Sans appel réseau ?'],
    },
  }
};

function TrueIcon() { return <span className="text-emerald-500 font-bold">✓</span>; }
function FalseIcon() { return <span className="text-red-400">✗</span>; }

export function HomePage() {
  const { language } = useApp();
  const t = content[language];

  return (
    <div className="space-y-16">
      {/* Hero */}
      <div className="text-center space-y-6 pt-4">
        <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-violet-50 dark:bg-violet-950/50 border border-violet-200 dark:border-violet-800 text-violet-700 dark:text-violet-300 text-xs font-medium">
          <Shield size={12} />
          {t.badge}
        </div>
        <h1 className="text-4xl sm:text-5xl font-bold text-slate-900 dark:text-white leading-tight tracking-tight">
          {t.headline}<br />
          <span className="text-violet-600 dark:text-violet-400">{t.headline2}</span>
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400 max-w-2xl mx-auto leading-relaxed">
          {t.description}
        </p>
        <div className="flex flex-wrap items-center justify-center gap-3">
          <Link
            to="/quickstart"
            className="inline-flex items-center gap-2 px-6 py-3 bg-violet-600 hover:bg-violet-700 text-white font-semibold rounded-xl shadow-lg shadow-violet-200 dark:shadow-none transition-all hover:scale-105"
          >
            {t.getStarted}
            <ArrowRight size={16} />
          </Link>
          <Link
            to="/api/data-signer"
            className="inline-flex items-center gap-2 px-6 py-3 border border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-300 font-semibold rounded-xl hover:bg-slate-50 dark:hover:bg-slate-800 transition-all"
          >
            <Code2 size={16} />
            {t.apiRef}
          </Link>
        </div>
        {/* Stats */}
        <div className="flex flex-wrap justify-center gap-8 pt-4">
          {[
            { value: '<1ms', label: language === 'en' ? 'Verification time' : 'Temps de vérification' },
            { value: '132', label: language === 'en' ? 'Tests passing' : 'Tests passants' },
            { value: '0', label: language === 'en' ? 'Shared secrets' : 'Secrets partagés' },
            { value: 'MIT', label: language === 'en' ? 'License' : 'Licence' },
          ].map(stat => (
            <div key={stat.value} className="text-center">
              <div className="text-2xl font-bold text-slate-900 dark:text-white">{stat.value}</div>
              <div className="text-xs text-slate-500 dark:text-slate-400">{stat.label}</div>
            </div>
          ))}
        </div>
      </div>

      {/* Trilemma table */}
      <div>
        <h2 className="text-2xl font-bold text-slate-900 dark:text-white mb-2">{t.trilemma.title}</h2>
        <p className="text-slate-600 dark:text-slate-400 mb-4">{t.whyDesc}</p>
        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800/50">
                {t.trilemma.cols.map(col => (
                  <th key={col} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{col}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
              {t.trilemma.rows.map((row, i) => (
                <tr key={i} className={row.highlight
                  ? 'bg-violet-50 dark:bg-violet-950/30 font-semibold'
                  : 'bg-white dark:bg-slate-900'
                }>
                  <td className={`px-4 py-3 ${row.highlight ? 'text-violet-700 dark:text-violet-300' : 'text-slate-700 dark:text-slate-300'}`}>
                    {row.highlight && <span className="mr-2">★</span>}{row.approach}
                  </td>
                  <td className="px-4 py-3 text-center">{row.revocable ? <TrueIcon /> : <FalseIcon />}</td>
                  <td className="px-4 py-3 text-center">{row.noSecret ? <TrueIcon /> : <FalseIcon />}</td>
                  <td className="px-4 py-3 text-center">{row.noCall ? <TrueIcon /> : <FalseIcon />}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Quick Start */}
      <div>
        <h2 className="text-2xl font-bold text-slate-900 dark:text-white mb-2">{t.quickStart}</h2>
        <p className="text-slate-600 dark:text-slate-400 mb-4">{t.quickStartDesc}</p>
        <CodeBlock code={QUICK_START_CODE} language="java" title="Complete authentication lifecycle" />
        <Link to="/quickstart" className="inline-flex items-center gap-1.5 text-violet-600 dark:text-violet-400 hover:underline text-sm font-medium mt-2">
          {t.learnMore} <ArrowRight size={14} />
        </Link>
      </div>

      {/* Features */}
      <div>
        <h2 className="text-2xl font-bold text-slate-900 dark:text-white mb-6">{t.features}</h2>
        <div className="grid sm:grid-cols-2 gap-4">
          {t.featuresList.map((f, i) => {
            const Icon = f.icon;
            return (
              <div key={i} className={`rounded-xl p-5 border border-slate-200 dark:border-slate-700 ${f.bg}`}>
                <div className="flex items-start gap-3">
                  <div className={`p-2 rounded-lg bg-white dark:bg-slate-800 ${f.color}`}>
                    <Icon size={18} />
                  </div>
                  <div>
                    <h3 className="font-semibold text-slate-900 dark:text-white mb-1">{f.title}</h3>
                    <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">{f.desc}</p>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Maven artifact */}
      <div className="rounded-xl border border-slate-200 dark:border-slate-700 overflow-hidden">
        <div className="px-5 py-4 bg-slate-50 dark:bg-slate-800/50 border-b border-slate-200 dark:border-slate-700 flex items-center gap-2">
          <Package size={16} className="text-slate-500" />
          <span className="font-semibold text-slate-900 dark:text-white text-sm">
            {language === 'en' ? 'Maven / Gradle coordinates' : 'Coordonnées Maven / Gradle'}
          </span>
        </div>
        <div className="p-5 space-y-3">
          <div className="flex items-center gap-2">
            <CheckCircle size={14} className="text-emerald-500 flex-shrink-0" />
            <code className="text-sm text-slate-700 dark:text-slate-300 font-mono">io.github.cyfko:veridot-core:4.0.1</code>
            <span className="text-xs text-slate-400">— {language === 'en' ? 'Core API (Java 25+)' : 'API centrale (Java 25+)'}</span>
          </div>
          <div className="flex items-center gap-2">
            <CheckCircle size={14} className="text-emerald-500 flex-shrink-0" />
            <code className="text-sm text-slate-700 dark:text-slate-300 font-mono">io.github.cyfko:veridot-kafka:4.0.1</code>
            <span className="text-xs text-slate-400">— {language === 'en' ? 'Kafka broker (Java 17+)' : 'Broker Kafka (Java 17+)'}</span>
          </div>
          <div className="flex items-center gap-2">
            <CheckCircle size={14} className="text-emerald-500 flex-shrink-0" />
            <code className="text-sm text-slate-700 dark:text-slate-300 font-mono">io.github.cyfko:veridot-databases:4.0.1</code>
            <span className="text-xs text-slate-400">— {language === 'en' ? 'SQL broker (Java 17+)' : 'Broker SQL (Java 17+)'}</span>
          </div>
          <Link to="/installation" className="inline-flex items-center gap-1.5 text-violet-600 dark:text-violet-400 text-sm hover:underline font-medium">
            {language === 'en' ? 'Full installation guide' : 'Guide d\'installation complet'} <ArrowRight size={13} />
          </Link>
        </div>
      </div>
    </div>
  );
}
