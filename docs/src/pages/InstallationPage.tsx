import { useApp } from '../context/AppContext';
import { CodeBlock } from '../components/CodeBlock';
import { Admonition } from '../components/Admonition';

const MAVEN_CORE = `<dependency>
  <groupId>io.github.cyfko</groupId>
  <artifactId>veridot-core</artifactId>
  <version>4.0.0</version>
</dependency>`;

const MAVEN_KAFKA = `<dependency>
  <groupId>io.github.cyfko</groupId>
  <artifactId>veridot-kafka</artifactId>
  <version>4.0.0</version>
</dependency>`;

const MAVEN_DB = `<dependency>
  <groupId>io.github.cyfko</groupId>
  <artifactId>veridot-databases</artifactId>
  <version>4.0.0</version>
</dependency>`;

const GRADLE = `dependencies {
    implementation 'io.github.cyfko:veridot-core:4.0.0'

    // Pick ONE broker:
    implementation 'io.github.cyfko:veridot-kafka:4.0.0'
    // OR
    implementation 'io.github.cyfko:veridot-databases:4.0.0'
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}`;

const MAVEN_FULL = `<properties>
  <maven.compiler.source>25</maven.compiler.source>
  <maven.compiler.target>25</maven.compiler.target>
</properties>

<dependencies>
  <!-- Core API — always required -->
  <dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-core</artifactId>
    <version>4.0.0</version>
  </dependency>

  <!-- Kafka broker — recommended for Kafka environments -->
  <dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-kafka</artifactId>
    <version>4.0.0</version>
  </dependency>
</dependencies>`;

const BROKER_CHOICE = `Your application needs to distribute public key metadata across service instances.
Choose the broker that matches your infrastructure:

  ┌─── Already running Kafka? ───────► veridot-kafka   (recommended)
  │      Sub-ms reads via RocksDB
  │      Fan-out to all consumers automatically
  │
  └─── No Kafka / prefer SQL? ───────► veridot-databases
         Works with any JDBC DataSource
         Good for existing DB-centric stacks
         Polling-based propagation`;

export function InstallationPage() {
  const { language } = useApp();

  return (
    <div className="prose-like space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">
          {language === 'en' ? 'Getting Started' : 'Démarrage'}
        </p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Installation' : 'Installation'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Add Veridot to your Java project. Requires Java 25+ for veridot-core, Java 17+ for broker modules.'
            : 'Ajoutez Veridot à votre projet Java. Nécessite Java 25+ pour veridot-core, Java 17+ pour les modules broker.'}
        </p>
      </div>

      <Admonition type="info" title={language === 'en' ? 'Requirements' : 'Prérequis'}>
        <ul className="list-disc pl-4 space-y-1 mt-1">
          <li><strong>veridot-core</strong>: Java 25+</li>
          <li><strong>veridot-kafka</strong>: Java 17+ · Apache Kafka 3.x+</li>
          <li><strong>veridot-databases</strong>: Java 17+ · Any JDBC-compatible database</li>
        </ul>
      </Admonition>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Choosing a Broker' : 'Choisir un Broker'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 mb-4">
          {language === 'en'
            ? 'veridot-core defines the API. You need exactly one broker module to handle transport and storage of protocol entries.'
            : 'veridot-core définit l\'API. Vous avez besoin d\'exactement un module broker pour gérer le transport et le stockage des entrées de protocole.'}
        </p>
        <CodeBlock code={BROKER_CHOICE} language="text" title="Decision guide" showLineNumbers={false} />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">Maven</h2>
        <p className="text-slate-600 dark:text-slate-400 mb-3">
          {language === 'en'
            ? 'Add to your pom.xml. Always include veridot-core plus one broker:'
            : 'Ajoutez à votre pom.xml. Incluez toujours veridot-core plus un broker :'}
        </p>
        <CodeBlock code={MAVEN_FULL} language="xml" title="pom.xml" />

        <div className="grid sm:grid-cols-3 gap-3 mt-4">
          <div>
            <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-2 uppercase tracking-wider">Core</p>
            <CodeBlock code={MAVEN_CORE} language="xml" showLineNumbers={false} />
          </div>
          <div>
            <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-2 uppercase tracking-wider">Kafka Broker</p>
            <CodeBlock code={MAVEN_KAFKA} language="xml" showLineNumbers={false} />
          </div>
          <div>
            <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-2 uppercase tracking-wider">SQL Broker</p>
            <CodeBlock code={MAVEN_DB} language="xml" showLineNumbers={false} />
          </div>
        </div>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">Gradle</h2>
        <CodeBlock code={GRADLE} language="groovy" title="build.gradle" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Module Overview' : 'Vue d\'ensemble des modules'}
        </h2>
        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800/50">
                {['Module', language === 'en' ? 'Artifact' : 'Artefact', 'Java', language === 'en' ? 'Role' : 'Rôle'].map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
              {[
                { module: 'veridot-core', artifact: 'io.github.cyfko:veridot-core:4.0.0', java: '25+', role: language === 'en' ? 'Core API, GenericSignerVerifier, TrustRoot, Protocol V4' : 'API centrale, GenericSignerVerifier, TrustRoot, Protocole V4' },
                { module: 'veridot-kafka', artifact: 'io.github.cyfko:veridot-kafka:4.0.0', java: '17+', role: language === 'en' ? 'Broker backed by Kafka + RocksDB' : 'Broker supporté par Kafka + RocksDB' },
                { module: 'veridot-databases', artifact: 'io.github.cyfko:veridot-databases:4.0.0', java: '17+', role: language === 'en' ? 'Broker backed by any JDBC DataSource' : 'Broker supporté par n\'importe quel DataSource JDBC' },
              ].map(row => (
                <tr key={row.module} className="bg-white dark:bg-slate-900">
                  <td className="px-4 py-3 font-mono text-violet-700 dark:text-violet-300 font-medium">{row.module}</td>
                  <td className="px-4 py-3 font-mono text-xs text-slate-600 dark:text-slate-400">{row.artifact}</td>
                  <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{row.java}</td>
                  <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{row.role}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <Admonition type="success" title={language === 'en' ? 'Ready to code?' : 'Prêt à coder ?'}>
        {language === 'en'
          ? 'Once the dependency is added, proceed to the Quick Start guide to write your first sign/verify loop in under 5 minutes.'
          : 'Une fois la dépendance ajoutée, consultez le guide Démarrage rapide pour écrire votre premier cycle sign/verify en moins de 5 minutes.'}
      </Admonition>
    </div>
  );
}
