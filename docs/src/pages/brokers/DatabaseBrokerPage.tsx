import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const SETUP_CODE = `// Configure a standard JDBC DataSource with SSL encryption enabled
HikariDataSource dataSource = new HikariDataSource();
dataSource.setJdbcUrl("jdbc:postgresql://db-server:5432/veridot?ssl=true&sslmode=verify-full");
dataSource.setUsername("veridot_app_user");
dataSource.setPassword("secure-db-password");

// Initialize Database Broker
Broker dbBroker = new DatabaseBroker(dataSource, "veridot_metadata");`;

const SQL_GRANTS = `-- 1. Permissions for Signer database user (Needs to write updates)
GRANT INSERT, UPDATE ON veridot_metadata TO veridot_signer_user;

-- 2. Permissions for Verifier database user (Only reads updates)
GRANT SELECT ON veridot_metadata TO veridot_verifier_user;`;

export function DatabaseBrokerPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">Brokers</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">SQL Database Broker</h1>
        <p className="text-sm font-mono text-slate-500 dark:text-slate-400 mb-4">io.github.cyfko.veridot.databases</p>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'The veridot-databases module implements the Broker contract using SQL databases. It supports automatic table generation and vendor-specific upsert operations.'
            : 'Le module veridot-databases implémente le contrat Broker en s\'appuyant sur une base de données relationnelle.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Supported Dialects' : 'Dialectes Supportés'}
        </h2>
        <ul className="list-disc pl-5 space-y-1 text-sm text-slate-600 dark:text-slate-400 mb-3">
          <li><strong>PostgreSQL / H2</strong>: Uses `ON CONFLICT (storage_key) DO UPDATE` syntax.</li>
          <li><strong>MySQL / MariaDB</strong>: Uses `ON DUPLICATE KEY UPDATE` syntax.</li>
          <li><strong>Oracle</strong>: Uses `MERGE INTO ... USING DUAL` syntax.</li>
          <li><strong>SQL Server</strong>: Uses `MERGE INTO ... WITH (HOLDLOCK)` syntax.</li>
        </ul>
        <Admonition type="warning" title={language === 'en' ? 'MariaDB Driver Compatibility' : 'Compatibilité du Pilote MariaDB'}>
          {language === 'en'
            ? 'When deploying against MariaDB, ensure that your JDBC connection driver reports "MySQL" in its metadata product name, or configure your database properties appropriately. If using a pure MariaDB driver, the dialect auto-detection might throw an UnsupportedOperationException on startup.'
            : 'Lors du déploiement avec MariaDB, assurez-vous que votre pilote JDBC rapporte "MySQL" dans le nom de produit de ses métadonnées, ou configurez les propriétés de votre base de données en conséquence.'}
        </Admonition>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Instantiation & SSL Config' : 'Initialisation & Chiffrement SSL'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 mb-3 text-sm">
          {language === 'en'
            ? 'Ensure SSL connection parameters are appended to the JDBC URL to protect token payloads in transit:'
            : 'Veillez à ce que les paramètres de connexion SSL soient ajoutés à l\'URL JDBC pour chiffrer les flux :' }
        </p>
        <CodeBlock code={SETUP_CODE} language="java" title="Creating the DatabaseBroker" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Least-Privilege Database Roles' : 'Rôles de Moindre Privilège'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 mb-3 text-sm">
          {language === 'en'
            ? 'In production, restrict SQL privileges. Separation of roles prevents verifiers from overwriting active configurations:'
            : 'En production, séparez les accès SQL. Restreindre les privilèges empêche les vérificateurs de polluer ou d\'altérer les données :'}
        </p>
        <CodeBlock code={SQL_GRANTS} language="sql" title="Least-privilege SQL grants" />
      </section>

      <Admonition type="warning" title={language === 'en' ? 'Disable Auto-DDL in Production' : 'Désactiver la création automatique (Auto-DDL)'}>
        {language === 'en'
          ? 'While DatabaseBroker automatically attempts to run CREATE TABLE statements on startup, you should disable this auto-DDL execution in production. Use Liquibase or Flyway schema migration tools instead to manage the schema and deny DDL/table modification rights to your application database users.'
          : 'Bien que le DatabaseBroker tente de créer la table au démarrage, désactivez la création automatique (Auto-DDL) en production. Utilisez des outils comme Liquibase ou Flyway et révoquez les droits de DDL sur l\'utilisateur applicatif.'}
      </Admonition>

      <section className="border-t border-slate-100 dark:border-slate-800 pt-6">
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Double Role as WatermarkStore' : 'Double Rôle comme WatermarkStore'}
        </h2>
        <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
          {language === 'en'
            ? 'The DatabaseBroker implements the WatermarkStore interface directly. It stores version watermarks securely in the same SQL table under a special non-colliding key prefix (0xFF + __watermark_snapshot__). This eliminates the need for local FileWatermarkStore configurations in stateless containerized environments.'
            : 'Le DatabaseBroker implémente directement l\'interface WatermarkStore. Il stocke les watermarks de version de manière sécurisée dans la même table SQL sous un préfixe spécial non conflictuel (0xFF + __watermark_snapshot__). Cela élimine le besoin d\'un stockage local FileWatermarkStore dans les environnements de conteneurs stateless.'}
        </p>
      </section>
    </div>
  );
}
