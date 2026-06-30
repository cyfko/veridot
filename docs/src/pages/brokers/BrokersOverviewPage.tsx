import { useApp } from '../../context/AppContext';
import { Admonition } from '../../components/Admonition';

export function BrokersOverviewPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">Brokers</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Choosing & Securing a Broker' : 'Choisir & Sécuriser un Broker'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'The Broker serves as the transport and persistence layer of Veridot. Learn how to select the right broker and secure it in production.'
            : 'Le Broker fait office de couche de transport et de stockage. Découvrez comment sélectionner le bon broker et le sécuriser en production.'}
        </p>
      </div>

      <section className="grid sm:grid-cols-2 gap-4">
        <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-5 bg-white dark:bg-slate-900">
          <h2 className="text-lg font-bold text-slate-900 dark:text-white mb-2">Apache Kafka</h2>
          <p className="text-sm text-slate-600 dark:text-slate-400 mb-4">
            {language === 'en'
              ? 'Recommended for high-performance and high-scale production. Broadcasts envelopes via a compacted topic, replicating state to local RocksDB caches.'
              : 'Recommandé pour la production à fort volume. Diffuse via un sujet compacté vers un cache RocksDB local.'}
          </p>
          <ul className="text-xs text-slate-500 space-y-1 pl-4 list-disc">
            <li>Sub-millisecond verification reads</li>
            <li>Automatic fan-out</li>
            <li>Eventual consistency model</li>
          </ul>
        </div>

        <div className="rounded-xl border border-slate-200 dark:border-slate-700 p-5 bg-white dark:bg-slate-900">
          <h2 className="text-lg font-bold text-slate-900 dark:text-white mb-2">SQL Databases</h2>
          <p className="text-sm text-slate-600 dark:text-slate-400 mb-4">
            {language === 'en'
              ? 'Ideal for database-centric stacks, small-scale deployments, or when running Kafka is operational overkill. Uses standard JDBC datasources.'
              : 'Idéal pour les architectures centrées sur les bases de données SQL. Utilise un DataSource JDBC classique.'}
          </p>
          <ul className="text-xs text-slate-500 space-y-1 pl-4 list-disc">
            <li>ACID-compliant durability</li>
            <li>Simple operational model</li>
            <li>Dialects for Postgres, MySQL, Oracle, SQL Server</li>
          </ul>
        </div>
      </section>

      <section className="space-y-4">
        <h2 className="text-xl font-bold text-slate-900 dark:text-white">
          {language === 'en' ? 'Why secure a broker if Veridot is broker-untrusted?' : 'Pourquoi sécuriser le broker si Veridot ne s\'appuie pas dessus ?'}
        </h2>
        <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
          {language === 'en'
            ? 'Veridot is broker-untrusted by construction: an attacker with full access to the broker cannot forge key epochs or inject fake configurations because verifiers validate signatures via the TrustRoot. However, leaving the broker unprotected exposes your cluster to critical operational risks:'
            : 'Veridot est indépendant de la sécurité du courtier : un attaquant ayant accès au broker ne peut pas forger de signatures d\'époque sans posséder la clé racine. Cependant, laisser le broker non sécurisé expose votre infrastructure à des risques réels :'}
        </p>
        <ul className="list-disc pl-5 space-y-2 text-sm text-slate-600 dark:text-slate-400">
          <li>
            <strong>Denial of Service (DoS)</strong>: 
            {language === 'en' 
              ? 'An attacker can flood the broker topic/table with garbage envelopes, bloating verifier CPU usage during verification signature checks and exhausting disk space on RocksDB caches.' 
              : 'Un attaquant peut inonder le broker avec des enveloppes invalides, saturant le processeur des vérificateurs lors des validations de signature et gonflant la mémoire de RocksDB.'}
          </li>
          <li>
            <strong>Confidentiality Leaks (INDIRECT Mode)</strong>: 
            {language === 'en' 
              ? 'In INDIRECT token mode, token payloads (containing sensitive claims, user emails, or roles) are stored inside the broker database/topic. If transit or storage is unencrypted, attackers can read this data.' 
              : 'En mode INDIRECT, la charge utile du token est stockée sur le Broker. Si le Broker n\'est pas chiffré, un intrus peut lire ces informations sensibles (emails, identités, rôles).'}
          </li>
          <li>
            <strong>Metadata Tampering (Replay Floods)</strong>: 
            {language === 'en' 
              ? 'Without write controls, an attacker can overwrite active configurations with older valid configuration envelopes to trigger cache sync delays.' 
              : 'Sans contrôle d\'écriture, un attaquant peut écraser des configurations actives par d\'anciennes enveloppes de configuration valides pour perturber la synchronisation.'}
          </li>
        </ul>
      </section>

      <Admonition type="security" title={language === 'en' ? 'Zero-Trust Integration' : 'Intégration Zero-Trust'}>
        {language === 'en'
          ? 'Always configure TLS encryption in transit, strict SASL/JDBC authentication, and Access Control Lists (ACLs) to ensure only authorized microservices can write to your Veridot topics or tables.'
          : 'Activez toujours le chiffrement TLS en transit, l\'authentification SASL/JDBC forte, et des listes de contrôle d\'accès (ACL) pour restreindre les écritures.'}
      </Admonition>
    </div>
  );
}
