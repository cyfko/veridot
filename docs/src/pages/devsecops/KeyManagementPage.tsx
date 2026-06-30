import { useApp } from '../../context/AppContext';
import { Admonition } from '../../components/Admonition';
import { Mermaid } from '../../components/Mermaid';

const DIAGRAM = `graph TD
    subgraph ControlPlane ["Control Plane & Issuance"]
        KMS["Cloud KMS / Vault HSM"] -->|"1. Sign Key Epoch once a day"| Issuer["Issuer Service"]
    end
    subgraph RuntimePath ["Runtime Path & Verification"]
        Issuer -->|"2. Publish KEY_EPOCH envelope"| Broker["Untrusted Broker"]
        User["User Request"] -->|"3. Present JWT"| Verifier["Verifier Service"]
        Verifier -->|"4. Resolve ephemeral public key locally"| LocalCache["Local Cache / RocksDB"]
        Broker -.->|"5. Replicate envelope"| LocalCache
    end`;

export function KeyManagementPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">DevSecOps</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Key & Trust Management' : 'Gestion des Clés & de la Confiance'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Operational runbook for planned root key rotations, emergency compromises, and configuring KMS integrations.'
            : 'Manuel opérationnel pour les rotations de clé racine, rétablissement après compromission et configurations KMS.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Decoupled KMS Architecture (No SPoF)' : 'Architecture Découplée (Pas de SPoF)'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 leading-relaxed mb-4">
          {language === 'en'
            ? 'A major concern is that the KMS or HSM holding the root private keys becomes a runtime Single Point of Failure (SPoF). Veridot prevents this by ensuring the verification path is completely isolated from the KMS. Verifiers validate envelopes locally using public keys and version watermarks.'
            : 'Pour éviter que le KMS ou l\'HSM ne soit un point unique de défaillance (SPoF) bloquant l\'authentification, Veridot isole le flux de validation. Le vérificateur n\'interroge jamais le KMS.'}
        </p>
        <Mermaid chart={DIAGRAM} caption={language === 'en' ? 'decoupled KMS architecture' : 'architecture KMS découplée'} />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Planned Key Rotation' : 'Rotation de Clés Planifiée'}
        </h2>
        <ul className="list-decimal pl-5 space-y-2 text-sm text-slate-600 dark:text-slate-400">
          <li><strong>Dual Trust Setup</strong>: Add the new root public key to your TrustRoot configurations alongside the old public key. Run all verifier pods with this configuration.</li>
          <li><strong>Re-signing Capabilities</strong>: Locate active capabilities in the Broker, generate copies signed by the new root key, and publish them with incremented versions to overwrite local caches.</li>
          <li><strong>Ephemeral Rollover</strong>: Allow active ephemeral key epochs to expire. Signers will automatically rotate and publish new epochs signed with the new capabilities.</li>
          <li><strong>Clean Up</strong>: Audit verifier logs to ensure no active sessions use the old root, then delete the old root private key from the KMS.</li>
        </ul>
      </section>

      <Admonition type="security" title={language === 'en' ? 'HSM Integration' : 'Intégration HSM'}>
        {language === 'en'
          ? 'Never store long-term private keys in plain text files. Implement AWS/GCP KMS or PKCS#11 HSM interfaces to sign capabilities and key epochs.'
          : 'Ne stockez jamais les clés privées à long terme en clair. Intégrez AWS/GCP KMS ou PKCS#11 HSM pour signer les enveloppes.'}
      </Admonition>
    </div>
  );
}
