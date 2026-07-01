import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const DELEGATED_TRUST = `public class VaultPublicKeyTrustRoot implements PublicKeyTrustRoot {
    private final VaultTemplate vaultTemplate;

    public VaultPublicKeyTrustRoot(VaultTemplate vaultTemplate) {
        this.vaultTemplate = vaultTemplate;
    }

    @Override
    public TrustIdentity resolve(String issuerId) {
        // Dynamic lookup in HashiCorp Vault key-value engine
        VaultResponse response = vaultTemplate.read("secret/data/veridot/trust/" + issuerId);
        String publicKeyPem = (String) response.getRequiredData().get("public_key");
        PublicKey publicKey = parsePemPublicKey(publicKeyPem);
        boolean isRoot = "root-signer-id".equals(issuerId);
        return new TrustIdentity(publicKey, isRoot);
    }
}`;

export function TrustRootProductionPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">Security</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'TrustRoot in Production' : 'TrustRoot en Production'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'The TrustRoot interface resolves issuer identities to long-term public keys. In production, avoid static file lookups in favor of dynamic secret stores.'
            : 'L\'interface TrustRoot résout les identités d\'émetteur en clés publiques. En production, préférez des requêtes vers un coffre-fort de secrets.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'KMS Integration' : 'Intégration KMS'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 mb-3">
          {language === 'en'
            ? 'For secure environments, implement a dynamic TrustRoot that pulls keys from cloud KMS providers (AWS KMS, GCP KMS) or HashiCorp Vault:'
            : 'Pour les environnements sécurisés, implémentez un TrustRoot dynamique interrogeant un gestionnaire de secrets (AWS KMS, Vault, etc.) :'}
        </p>
        <CodeBlock code={DELEGATED_TRUST} language="java" title="Vault-backed TrustRoot implementation" />
      </section>

      <Admonition type="security" title={language === 'en' ? 'Key Verification Safety' : 'Sécurité de la Validation des Clés'}>
        {language === 'en'
          ? 'Always ensure that the fetched long-term keys are validated against your PKI (Certificate Authority) to prevent unauthorized public keys from being registered in the TrustRoot.'
          : 'Validez toujours les clés récupérées auprès de votre PKI (Autorité de Certification) pour éviter l\'enregistrement de clés publiques malveillantes.'}
      </Admonition>
    </div>
  );
}
