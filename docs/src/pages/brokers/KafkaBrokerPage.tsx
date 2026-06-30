import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const SETUP_CODE = `Properties props = new Properties();
props.put("bootstrap.servers", "kafka:9092");
props.put("embedded.db.path", "/var/lib/veridot-rocks");
props.put("broker.topic", "veridot-v4-metadata");

// ── Production Security Configurations ────────────────────────────
props.put("security.protocol", "SASL_SSL");
props.put("ssl.truststore.location", "/var/private/ssl/kafka.truststore.jks");
props.put("ssl.truststore.password", "truststore-password");
props.put("sasl.mechanism", "SCRAM-SHA-512");
props.put("sasl.jaas.config", 
    "org.apache.kafka.common.security.scram.ScramLoginModule required " +
    "username=\\"veridot-client\\" password=\\"client-secret\\";"
);

// Initialize secure Kafka Broker
Broker kafkaBroker = new KafkaBroker(props);`;

const ACL_COMMANDS = `# 1. Give Signer instances permission to WRITE to the Veridot topic
kafka-acls.sh --bootstrap-server localhost:9092 \\
  --add --allow-principal User:veridot-signer-service \\
  --operation Write --topic veridot-v4-metadata

# 2. Give Verifier instances permission to READ from the Veridot topic
kafka-acls.sh --bootstrap-server localhost:9092 \\
  --add --allow-principal User:veridot-verifier-service \\
  --operation Read --topic veridot-v4-metadata`;

export function KafkaBrokerPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">Brokers</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">Kafka Broker</h1>
        <p className="text-sm font-mono text-slate-500 dark:text-slate-400 mb-4">io.github.cyfko.veridot.kafka</p>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'The veridot-kafka module implements the Broker contract using Apache Kafka as a transport layer and RocksDB as a local verifier cache.'
            : 'Le module veridot-kafka implémente le contrat Broker en utilisant Apache Kafka comme transport et RocksDB comme cache de vérification local.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Configuration Properties' : 'Propriétés de Configuration'}
        </h2>
        <ul className="list-disc pl-5 space-y-1 text-sm text-slate-600 dark:text-slate-400">
          <li><strong>bootstrap.servers</strong>: Kafka cluster bootstrap endpoints (required).</li>
          <li><strong>embedded.db.path</strong>: Filesystem path to initialize the local RocksDB instance (required).</li>
          <li><strong>broker.topic</strong>: Compacted topic name (default: `veridot-token-metadata`).</li>
        </ul>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Instantiation & Security Config' : 'Initialisation & Configuration de Sécurité'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 mb-3 text-sm">
          {language === 'en'
            ? 'Enable SASL_SSL and configure SCRAM-SHA-512 authentication to encrypt token payloads in transit (crucial for INDIRECT mode) and secure the broker topic:'
            : 'Activez SASL_SSL et configurez l\'authentification SCRAM-SHA-512 pour chiffrer les flux (essentiel pour le mode INDIRECT) et sécuriser l\'accès au topic :'}
        </p>
        <CodeBlock code={SETUP_CODE} language="java" title="Creating the KafkaBroker" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Access Control Lists (ACLs)' : 'Listes de Contrôle d\'Accès (ACLs)'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 mb-3 text-sm">
          {language === 'en'
            ? 'Enforce least-privilege access rules to prevent unauthorized clients from writing or flooding the metadata topic:'
            : 'Appliquez le principe du moindre privilège pour empêcher les clients non autorisés d\'écrire ou d\'inonder le topic de métadonnées :'}
        </p>
        <CodeBlock code={ACL_COMMANDS} language="bash" title="Kafka ACL configuration commands" />
      </section>

      <Admonition type="info" title={language === 'en' ? 'Offset Commits' : 'Engagements d\'Offsets (Commit)'}>
        {language === 'en'
          ? 'The KafkaBroker disables enable.auto.commit in favor of manual commits. It commits consumer offsets synchronously only after envelopes successfully parse and write to the local RocksDB instance, preventing data loss on consumer restarts.'
          : 'Le KafkaBroker désactive enable.auto.commit pour effectuer des commits manuels synchrones après écriture dans RocksDB.'}
      </Admonition>
    </div>
  );
}
