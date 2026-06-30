import { useApp } from '../../context/AppContext';
import { Mermaid } from '../../components/Mermaid';
import { Admonition } from '../../components/Admonition';

const LOCAL_DIAGRAM = `graph LR
    subgraph "Developer Workstation"
        App[Your Application] --> GSV[GenericSignerVerifier]
        GSV --> KB[KafkaBroker]
        KB --> RDB[(RocksDB local cache)]
        KB --> EK[Embedded Kafka / Testcontainers]
        GSV --> TR[PublicKeyTrustRoot\nfrom local PEM file]
    end
    style EK fill:#fef3c7,stroke:#d97706
    style TR fill:#fee2e2,stroke:#dc2626`;

const ENTERPRISE_DIAGRAM = `graph TB
    subgraph "Auth Service Pod"
        AS[Auth Service] --> GSV1[GenericSignerVerifier]
        GSV1 --> KB1[KafkaBroker + RocksDB]
        GSV1 --> TR1[DelegatedTrustRoot]
    end

    subgraph "API Service Pod"
        API[API Service] --> GSV2[GenericSignerVerifier]
        GSV2 --> KB2[KafkaBroker + RocksDB]
        GSV2 --> TR2[DelegatedTrustRoot]
    end

    subgraph "Kafka Cluster"
        K[(Kafka Broker\nTLS + SASL)]
    end

    subgraph "KMS"
        V[HashiCorp Vault\nor AWS KMS]
    end

    subgraph "PKI"
        CA[TrustRoot\nPublic Keys in\nVault KV or ConfigMap]
    end

    KB1 --> K
    KB2 --> K
    TR1 --> V
    TR2 --> V
    V --> CA

    style K fill:#fef3c7,stroke:#d97706
    style V fill:#ede9fe,stroke:#7c3aed
    style CA fill:#dcfce7,stroke:#16a34a`;

const K8S_DIAGRAM = `graph TB
    subgraph "Kubernetes Cluster"
        subgraph "Namespace: auth"
            AS[Auth Deployment] --> CM[ConfigMap: signer-id]
            AS --> SEC[Secret: private-key-ref → Vault]
        end

        subgraph "Namespace: api"
            API[API Deployment] --> CM2[ConfigMap: trust-dir]
        end

        subgraph "Namespace: infra"
            K[(Kafka StatefulSet\nwith TLS)]
            VA[Vault Agent\nSidecar]
        end

        AS --> K
        API --> K
        AS --> VA
        API --> VA
    end

    subgraph "External"
        VS[Vault Server\nor AWS KMS]
    end

    VA --> VS
    style VA fill:#ede9fe,stroke:#7c3aed
    style VS fill:#ede9fe,stroke:#7c3aed`;

export function DeploymentPage() {
  const { language } = useApp();

  const models = language === 'en' ? [
    {
      title: 'Local development',
      desc: 'Single workstation. Use Testcontainers or embedded Kafka. Load keys from local PEM files. PublicKeyTrustRoot is sufficient.',
      components: [
        { name: 'Kafka / Testcontainers', why: 'Required for protocol entry propagation. In dev, use embedded Kafka or testcontainers.' },
        { name: 'RocksDB (embedded)', why: 'Local cache for KEY_EPOCH and LIVENESS entries. Created automatically by KafkaBroker.' },
        { name: 'Local PEM files', why: 'Long-term key and TrustRoot in dev. Never use in production.' },
      ],
      diagram: LOCAL_DIAGRAM,
    },
    {
      title: 'Enterprise (on-premise)',
      desc: 'Multi-service deployment with a shared Kafka cluster, DelegatedTrustRoot backed by Vault or KMS, and TLS-secured communication.',
      components: [
        { name: 'Kafka Cluster (TLS + SASL)', why: 'Required. Transports all Protocol V4 entries between service instances. TLS prevents eavesdropping. SASL provides authentication to the broker.' },
        { name: 'RocksDB (per-service)', why: 'Required. Local persistent cache for sub-millisecond reads. Created automatically by KafkaBroker from the embedded.db.path property.' },
        { name: 'HashiCorp Vault / AWS KMS', why: 'Recommended. The long-term private key and TrustRoot resolution live here. DelegatedTrustRoot delegates signature verification to Vault Transit Engine. Private key never leaves the KMS.' },
        { name: 'Vault KV or Kubernetes ConfigMap', why: 'Stores TrustRoot public keys (not secrets — public keys are not sensitive). Must be accessible by all verifying services.' },
      ],
      diagram: ENTERPRISE_DIAGRAM,
    },
    {
      title: 'Kubernetes',
      desc: 'Cloud-native deployment with Vault Agent sidecar for secret injection, Kafka as StatefulSet or managed service.',
      components: [
        { name: 'Vault Agent Sidecar', why: 'Injects the long-term private key as a file or environment variable at pod startup. The key is fetched from Vault and is never stored in etcd or a Kubernetes Secret in plaintext.' },
        { name: 'Kubernetes ConfigMap', why: 'Stores the TrustRoot public keys (non-sensitive). Mounted as a file in pods.' },
        { name: 'Kafka (StatefulSet or managed)', why: 'Confluent Cloud, AWS MSK, or self-hosted. The broker must support TLS and SASL for production use.' },
        { name: 'PodDisruptionBudget', why: 'Ensures at least one replica of each service remains during rolling updates, preventing auth gaps.' },
      ],
      diagram: K8S_DIAGRAM,
    },
  ] : [
    {
      title: 'Développement local',
      desc: 'Poste de travail unique. Utilisez Testcontainers ou Kafka embarqué. Chargez les clés depuis des fichiers PEM locaux. PublicKeyTrustRoot est suffisant.',
      components: [
        { name: 'Kafka / Testcontainers', why: 'Requis pour la propagation des entrées de protocole. En dev, utilisez Kafka embarqué ou testcontainers.' },
        { name: 'RocksDB (embarqué)', why: 'Cache local pour les entrées KEY_EPOCH et LIVENESS. Créé automatiquement par KafkaBroker.' },
        { name: 'Fichiers PEM locaux', why: 'Clé long terme et TrustRoot en dev. N\'utilisez jamais en production.' },
      ],
      diagram: LOCAL_DIAGRAM,
    },
    {
      title: 'Entreprise (on-premise)',
      desc: 'Déploiement multi-services avec un cluster Kafka partagé, DelegatedTrustRoot soutenu par Vault ou KMS, et communication sécurisée TLS.',
      components: [
        { name: 'Cluster Kafka (TLS + SASL)', why: 'Requis. Transporte toutes les entrées Protocole V4 entre les instances de service. TLS prévient l\'écoute. SASL fournit l\'authentification au broker.' },
        { name: 'RocksDB (par service)', why: 'Requis. Cache persistant local pour les lectures sous-milliseconde. Créé automatiquement par KafkaBroker depuis la propriété embedded.db.path.' },
        { name: 'HashiCorp Vault / AWS KMS', why: 'Recommandé. La clé privée long terme et la résolution TrustRoot vivent ici. DelegatedTrustRoot délègue la vérification de signature au Vault Transit Engine. La clé privée ne quitte jamais le KMS.' },
        { name: 'Vault KV ou ConfigMap Kubernetes', why: 'Stocke les clés publiques TrustRoot (pas des secrets — les clés publiques ne sont pas sensibles). Doit être accessible par tous les services vérificateurs.' },
      ],
      diagram: ENTERPRISE_DIAGRAM,
    },
  ];

  return (
    <div className="space-y-10">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">DevSecOps</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Deployment Models' : 'Modèles de déploiement'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Progressive deployment models from local development to enterprise-grade production. Every architectural component is justified by Veridot\'s actual requirements — not by popularity.'
            : 'Modèles de déploiement progressifs du développement local à la production de niveau entreprise. Chaque composant architectural est justifié par les exigences réelles de Veridot — pas par leur popularité.'}
        </p>
      </div>

      <Admonition type="info" title={language === 'en' ? 'Architecture principle' : 'Principe d\'architecture'}>
        {language === 'en'
          ? 'Every deployment component in this guide is present because Veridot\'s protocol requires it. The broker must provide Put, Get, and Snapshot operations. The TrustRoot must be independent of the broker. Key rotation requires local ephemeral key generation. No component is included "just in case".'
          : 'Chaque composant de déploiement dans ce guide est présent parce que le protocole Veridot le requiert. Le broker doit fournir les opérations Put, Get et Snapshot. La TrustRoot doit être indépendante du broker. La rotation des clés nécessite la génération de clés éphémères locales. Aucun composant n\'est inclus "au cas où".'}
      </Admonition>

      {models.map(model => (
        <section key={model.title}>
          <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-2">{model.title}</h2>
          <p className="text-slate-600 dark:text-slate-400 mb-4 text-sm">{model.desc}</p>
          <Mermaid chart={model.diagram} caption={model.title} />
          <div className="mt-4 space-y-3">
            <p className="text-sm font-semibold text-slate-700 dark:text-slate-300">
              {language === 'en' ? 'Component justifications:' : 'Justifications des composants :'}
            </p>
            {model.components.map(c => (
              <div key={c.name} className="flex gap-3 border border-slate-200 dark:border-slate-700 rounded-lg p-3 bg-white dark:bg-slate-900">
                <div className="flex-shrink-0 w-1 rounded-full bg-violet-400 dark:bg-violet-600" />
                <div>
                  <p className="font-semibold text-slate-900 dark:text-white text-sm">{c.name}</p>
                  <p className="text-sm text-slate-600 dark:text-slate-400 mt-0.5">{c.why}</p>
                </div>
              </div>
            ))}
          </div>
        </section>
      ))}

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Kubernetes deployment' : 'Déploiement Kubernetes'}
        </h2>
        <Mermaid chart={K8S_DIAGRAM} caption={language === 'en' ? 'Kubernetes with Vault Agent sidecar' : 'Kubernetes avec sidecar Vault Agent'} />
        <div className="mt-4 space-y-3">
          {(language === 'en' ? [
            { name: 'Vault Agent Sidecar', why: 'Injects the long-term private key at pod startup. Never stored in etcd or Kubernetes Secrets in plaintext.' },
            { name: 'Kubernetes ConfigMap', why: 'Stores TrustRoot public keys (non-sensitive). Mounted as files in pods and shared across namespaces.' },
            { name: 'NetworkPolicy', why: 'Restricts Kafka access to only authorized pods. Prevents lateral movement if a service is compromised.' },
            { name: 'PodDisruptionBudget', why: 'Ensures auth service availability during rolling updates and node drains.' },
          ] : [
            { name: 'Sidecar Vault Agent', why: 'Injecte la clé privée long terme au démarrage du pod. Jamais stockée dans etcd ou les Secrets Kubernetes en clair.' },
            { name: 'ConfigMap Kubernetes', why: 'Stocke les clés publiques TrustRoot (non sensibles). Montées comme fichiers dans les pods et partagées entre namespaces.' },
            { name: 'NetworkPolicy', why: 'Restreint l\'accès Kafka aux seuls pods autorisés. Prévient le mouvement latéral si un service est compromis.' },
          ]).map(c => (
            <div key={c.name} className="flex gap-3 border border-slate-200 dark:border-slate-700 rounded-lg p-3 bg-white dark:bg-slate-900">
              <div className="flex-shrink-0 w-1 rounded-full bg-violet-400 dark:bg-violet-600" />
              <div>
                <p className="font-semibold text-slate-900 dark:text-white text-sm">{c.name}</p>
                <p className="text-sm text-slate-600 dark:text-slate-400 mt-0.5">{c.why}</p>
              </div>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
