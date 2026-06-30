import { useApp } from '../context/AppContext';
import { Admonition } from '../components/Admonition';
import { Mermaid } from '../components/Mermaid';

const THREAT_MODEL = `graph TB
    subgraph "Trust Boundary"
        TR[TrustRoot / KMS]
        PK[Long-term Private Key]
    end

    subgraph "Untrusted Zone"
        B[(Broker: Kafka / SQL)]
        N1[Node 1]
        N2[Node 2]
        ATK[Attacker with broker write access]
    end

    PK -->|signs KEY_EPOCH| N1
    TR -->|resolves issuer| N1
    TR -->|resolves issuer| N2
    N1 -->|publishes signed entry| B
    B -->|delivers| N2
    N2 -->|verifies via TrustRoot| TR

    ATK -.->|attempts forged entry| B
    B -.->|structural check only| B
    N2 -.->|TrustRoot rejects forged entry| ATK

    style TR fill:#ede9fe,stroke:#7c3aed
    style PK fill:#ede9fe,stroke:#7c3aed
    style ATK fill:#fee2e2,stroke:#dc2626
    style B fill:#fef3c7,stroke:#d97706`;

export function SecurityModelPage() {
  const { language } = useApp();

  const threats = language === 'en' ? [
    {
      threat: 'Broker injection of forged key material',
      mitigation: 'TrustRoot resolution + mandatory signature verification (§3.4, §5.4). An attacker with broker write access cannot produce a valid KEY_EPOCH entry without the long-term private key.',
      code: '—',
    },
    {
      threat: 'Forged or unauthorized configuration change',
      mitigation: 'Mandatory CAPABILITY verification before processing any CONFIG or FENCE entry. No default grant exists (§6.4, §7.4).',
      code: 'V4102, V4103',
    },
    {
      threat: 'State rollback via broker storage overwrite',
      mitigation: 'Monotonic version invariant enforced locally (§11.1). The processor\'s watermark never regresses, even if the broker replaces or deletes stored bytes.',
      code: 'V4201',
    },
    {
      threat: 'Silent treatment of revoked sessions as valid',
      mitigation: 'Positive-proof liveness model. Absence, invalidity, or expiry of a LIVENESS(ACTIVE) entry all produce the same outcome: rejection (§8.3).',
      code: 'V4202',
    },
    {
      threat: 'Race condition on session capacity mutation',
      mitigation: 'Mandatory FENCE token with strict monotonic ordering. Concurrent processors cannot both succeed for the same admission slot (§9.4, §10.3).',
      code: 'V4301',
    },
    {
      threat: 'Replay of a stale entry',
      mitigation: 'Monotonic version invariant (§11.1). The timestamp field is advisory and plays no role in replay prevention.',
      code: 'V4201',
    },
    {
      threat: 'Loss of a broker delivery in transit',
      mitigation: 'Periodic full-scope snapshot reconciliation (§11.4). The processor re-reads the full scope at configurable intervals (at most every 60 minutes).',
      code: '—',
    },
    {
      threat: 'Algorithm confusion attack on JWT',
      mitigation: 'Verifiers MUST check that the JWT "alg" header matches the Algorithm declared in KEY_EPOCH (§13.1). Mismatch causes immediate rejection.',
      code: 'V4204',
    },
    {
      threat: 'Side-channel timing attack on signature verification',
      mitigation: 'All signature verifications MUST use timing-safe operations. Ed25519 is mathematically constant-time and preferred (§13.1, NIST SP 800-186).',
      code: '—',
    },
    {
      threat: 'Local watermark tampering (rollback on restart)',
      mitigation: 'The persisted watermark snapshot MUST be cryptographically protected (HMAC using a key derived from the long-term private key). Integrity failure triggers full reconciliation (§12.3.1).',
      code: '—',
    },
  ] : [
    {
      threat: 'Injection de matériel de clé forgé par le broker',
      mitigation: 'Résolution TrustRoot + vérification de signature obligatoire (§3.4, §5.4). Un attaquant avec accès en écriture au broker ne peut pas produire une entrée KEY_EPOCH valide sans la clé privée long terme.',
      code: '—',
    },
    {
      threat: 'Changement de configuration forgé ou non autorisé',
      mitigation: 'Vérification CAPABILITY obligatoire avant de traiter toute entrée CONFIG ou FENCE. Aucune autorisation par défaut n\'existe (§6.4, §7.4).',
      code: 'V4102, V4103',
    },
    {
      threat: 'Rollback d\'état via écrasement du stockage broker',
      mitigation: 'Invariant de version monotone appliqué localement (§11.1). Le watermark du processeur ne régresse jamais, même si le broker remplace ou supprime les octets stockés.',
      code: 'V4201',
    },
    {
      threat: 'Traitement silencieux des sessions révoquées comme valides',
      mitigation: 'Modèle de liveness à preuve positive. L\'absence, l\'invalidité ou l\'expiration d\'une entrée LIVENESS(ACTIVE) produisent tous le même résultat : rejet (§8.3).',
      code: 'V4202',
    },
    {
      threat: 'Condition de course sur la mutation de capacité de session',
      mitigation: 'Token FENCE obligatoire avec ordre monotone strict. Les processeurs concurrents ne peuvent pas tous deux réussir pour le même slot d\'admission (§9.4, §10.3).',
      code: 'V4301',
    },
    {
      threat: 'Relecture d\'une entrée périmée',
      mitigation: 'Invariant de version monotone (§11.1). Le champ timestamp est consultatif et ne joue aucun rôle dans la prévention de la relecture.',
      code: 'V4201',
    },
    {
      threat: 'Perte d\'une livraison broker en transit',
      mitigation: 'Réconciliation périodique par snapshot de scope complet (§11.4). Le processeur relit le scope complet à intervalles configurables (au plus toutes les 60 minutes).',
      code: '—',
    },
    {
      threat: 'Attaque par confusion d\'algorithme sur JWT',
      mitigation: 'Les vérificateurs DOIVENT vérifier que l\'en-tête "alg" du JWT correspond à l\'Algorithm déclaré dans KEY_EPOCH (§13.1). Une inadéquation entraîne un rejet immédiat.',
      code: 'V4204',
    },
  ];

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">
          {language === 'en' ? 'Security' : 'Sécurité'}
        </p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Security Model' : 'Modèle de sécurité'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Veridot\'s security model is designed around one principle: the broker is transport and storage only — never an authority. All trust flows through the TrustRoot, independently of the broker.'
            : 'Le modèle de sécurité de Veridot est conçu autour d\'un principe : le broker est transport et stockage uniquement — jamais une autorité. Toute la confiance passe par la TrustRoot, indépendamment du broker.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Trust architecture' : 'Architecture de confiance'}
        </h2>
        <Mermaid chart={THREAT_MODEL} caption={language === 'en' ? 'Veridot trust boundaries' : 'Frontières de confiance Veridot'} />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Threat mitigation table (Protocol V4 §13.2)' : 'Tableau de mitigation des menaces (Protocole V4 §13.2)'}
        </h2>
        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800/50">
                {(language === 'en' ? ['Threat', 'Mitigation', 'Error code'] : ['Menace', 'Mitigation', 'Code d\'erreur']).map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
              {threats.map((t, i) => (
                <tr key={i} className={i % 2 === 0 ? 'bg-white dark:bg-slate-900' : 'bg-slate-50/50 dark:bg-slate-900/50'}>
                  <td className="px-4 py-3 text-slate-700 dark:text-slate-300 font-medium text-sm">{t.threat}</td>
                  <td className="px-4 py-3 text-slate-600 dark:text-slate-400 text-sm">{t.mitigation}</td>
                  <td className="px-4 py-3 font-mono text-xs text-violet-700 dark:text-violet-300">{t.code}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Residual risks (§13.5)' : 'Risques résiduels (§13.5)'}
        </h2>
        <Admonition type="warning" title={language === 'en' ? 'What Veridot cannot protect against' : 'Ce contre quoi Veridot ne peut pas protéger'}>
          <ul className="list-disc pl-4 space-y-1 mt-1">
            {(language === 'en' ? [
              'Compromise of a TrustRoot-resolvable long-term private key. Key custody is outside this specification\'s scope.',
              'Unavailability of the fencing authority for a scope, causing capacity-affecting mutations to stall.',
              'Resource exhaustion from unbounded entry volume. Deployments should apply rate limiting and storage quotas at the transport layer.',
            ] : [
              'Compromission d\'une clé privée long terme résolvable par TrustRoot. La garde des clés est en dehors du périmètre de cette spécification.',
              'Indisponibilité de l\'autorité de fencing pour un scope, causant le blocage des mutations affectant la capacité.',
              'Épuisement des ressources dû à un volume d\'entrées non borné. Les déploiements doivent appliquer la limitation de débit et les quotas de stockage au niveau de la couche de transport.',
            ]).map((r, i) => (
              <li key={i}>{r}</li>
            ))}
          </ul>
        </Admonition>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Broker failure semantics' : 'Sémantique des défaillances du broker'}
        </h2>
        <div className="space-y-3">
          {(language === 'en' ? [
            { title: 'TrustRoot unavailable', behavior: 'The processor MUST fail closed. All pending verifications requiring TrustRoot resolution are rejected. Falling back to accepting entries without trust resolution is explicitly forbidden (§13.4).' },
            { title: 'Broker unavailable (during verify)', behavior: 'Treated as a definitive rejection. The processor MUST fail closed — it MUST NOT treat an unreachable session as active. Transport errors must be logged separately from trust/liveness errors (§12.4).' },
            { title: 'Broker unavailable (during sign)', behavior: 'sign() throws BrokerTransportException. The signing operation fails atomically — no token is issued, no KEY_EPOCH is committed.' },
          ] : [
            { title: 'TrustRoot indisponible', behavior: 'Le processeur DOIT échouer fermé. Toutes les vérifications en attente nécessitant la résolution TrustRoot sont rejetées. Il est explicitement interdit de revenir à accepter des entrées sans résolution de trust (§13.4).' },
            { title: 'Broker indisponible (pendant verify)', behavior: 'Traité comme un rejet définitif. Le processeur DOIT échouer fermé — il NE DOIT PAS traiter une session injoignable comme active. Les erreurs de transport doivent être journalisées séparément des erreurs de trust/liveness (§12.4).' },
            { title: 'Broker indisponible (pendant sign)', behavior: 'sign() lance BrokerTransportException. L\'opération de signature échoue atomiquement — aucun token n\'est émis, aucun KEY_EPOCH n\'est engagé.' },
          ]).map(item => (
            <div key={item.title} className="border border-slate-200 dark:border-slate-700 rounded-xl p-4 bg-white dark:bg-slate-900">
              <p className="font-semibold text-slate-900 dark:text-white text-sm mb-1">{item.title}</p>
              <p className="text-sm text-slate-600 dark:text-slate-400">{item.behavior}</p>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
