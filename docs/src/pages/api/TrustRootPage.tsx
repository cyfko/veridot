import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const PUBLIC_KEY_TRUST_ROOT = `// PublicKeyTrustRoot: you provide the public key, Veridot verifies
TrustRoot trust = new PublicKeyTrustRoot(signerId -> {
    // Load the long-term public key for this signerId.
    // This function is called once per unique signerId.
    // You can return a cached result.
    byte[] pem = Files.readAllBytes(
        Paths.get("/etc/veridot/trust/" + signerId + ".pub.pem"));
    return parsePemPublicKey(pem); // your PEM → PublicKey helper
});`;

const DELEGATED_TRUST_ROOT = `// DelegatedTrustRoot: you delegate verification to a KMS or HSM
TrustRoot trust = new DelegatedTrustRoot((signerId, canonicalBytes, signature) -> {
    // Your KMS or HSM verifies the RSA signature.
    // The long-term private key never leaves the KMS.
    return vaultTransitEngine.verify(
        signerId,        // Vault key name
        canonicalBytes,  // bytes to verify
        signature        // RSA signature to check
    );
});`;

const VAULT_EXAMPLE = `// Production example: HashiCorp Vault Transit Engine
TrustRoot trust = new DelegatedTrustRoot((signerId, bytes, sig) -> {
    VaultTransitVerifyRequest req = VaultTransitVerifyRequest.builder()
        .input(Base64.getEncoder().encodeToString(bytes))
        .signature("vault:v1:" + Base64.getEncoder().encodeToString(sig))
        .signatureAlgorithm("pkcs1v15")
        .hashAlgorithm("sha2-256")
        .build();

    VaultTransitVerifyResponse resp = vaultClient.opsForTransit()
        .verify(signerId, req);

    return resp.isValid();
});`;

const AWS_KMS_EXAMPLE = `// Production example: AWS KMS
TrustRoot trust = new DelegatedTrustRoot((signerId, bytes, sig) -> {
    VerifyRequest request = VerifyRequest.builder()
        .keyId("arn:aws:kms:eu-west-1:123456789:key/" + signerId)
        .message(SdkBytes.fromByteArray(bytes))
        .signature(SdkBytes.fromByteArray(sig))
        .messageType(MessageType.RAW)
        .signingAlgorithm(SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256)
        .build();

    VerifyResponse response = kmsClient.verify(request);
    return response.signatureValid();
});`;

const TRUST_IDENTITY = `// TrustIdentity: returned by TrustRoot.resolve()
// Used internally by the verification pipeline.
// If you implement a custom TrustRoot, return a TrustIdentity.
public sealed interface TrustRoot permits PublicKeyTrustRoot, DelegatedTrustRoot {
    TrustIdentity resolve(String issuer);
}`;

export function TrustRootPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">API Reference</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-1">TrustRoot</h1>
        <p className="text-sm font-mono text-slate-500 dark:text-slate-400 mb-4">io.github.cyfko.veridot.core</p>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'The sole source of cryptographic trust in the Veridot system. Resolves issuer identifiers to long-term public keys, independently of the broker. Two implementations are provided: PublicKeyTrustRoot and DelegatedTrustRoot.'
            : 'L\'unique source de confiance cryptographique dans le système Veridot. Résout les identifiants d\'émetteur en clés publiques long terme, indépendamment du broker. Deux implémentations sont fournies : PublicKeyTrustRoot et DelegatedTrustRoot.'}
        </p>
      </div>

      <Admonition type="security" title={language === 'en' ? 'TrustRoot is the security boundary' : 'TrustRoot est la frontière de sécurité'}>
        {language === 'en'
          ? 'The TrustRoot is what makes the broker untrusted. Even with full write access to Kafka or the SQL database, an attacker cannot forge a valid KEY_EPOCH entry without possessing the long-term private key corresponding to a TrustRoot-resolvable issuer. The TrustRoot must be provisioned through a secure, out-of-band channel — never through the broker itself.'
          : 'La TrustRoot est ce qui rend le broker non fiable. Même avec un accès complet en écriture à Kafka ou la base SQL, un attaquant ne peut pas forger une entrée KEY_EPOCH valide sans posséder la clé privée long terme correspondant à un émetteur résolvable par TrustRoot. La TrustRoot doit être provisionnée via un canal sécurisé hors-bande — jamais via le broker lui-même.'}
      </Admonition>

      <div className="rounded-xl border border-slate-200 dark:border-slate-700 overflow-hidden">
        <div className="px-5 py-3 bg-slate-50 dark:bg-slate-800/50 border-b border-slate-200 dark:border-slate-700">
          <span className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">
            {language === 'en' ? 'Interface declaration' : 'Déclaration de l\'interface'}
          </span>
        </div>
        <CodeBlock code={TRUST_IDENTITY} language="java" showLineNumbers={false} />
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Implementations' : 'Implémentations'}
        </h2>

        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700 mb-6">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800/50">
                {(language === 'en' ? ['Implementation', 'Best for', 'Private key location'] : ['Implémentation', 'Idéal pour', 'Emplacement clé privée']).map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800 bg-white dark:bg-slate-900">
              <tr>
                <td className="px-4 py-3 font-mono text-violet-700 dark:text-violet-300 font-medium">PublicKeyTrustRoot</td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{language === 'en' ? 'Dev/staging, Vault KV, ConfigMaps, key files' : 'Dev/staging, Vault KV, ConfigMaps, fichiers de clé'}</td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{language === 'en' ? 'Anywhere the public key is stored; private key remains with issuer' : 'Partout où la clé publique est stockée ; la clé privée reste chez l\'émetteur'}</td>
              </tr>
              <tr>
                <td className="px-4 py-3 font-mono text-violet-700 dark:text-violet-300 font-medium">DelegatedTrustRoot</td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{language === 'en' ? 'Production: Vault Transit, AWS KMS, GCP KMS, Azure Key Vault, HSM' : 'Production : Vault Transit, AWS KMS, GCP KMS, Azure Key Vault, HSM'}</td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{language === 'en' ? 'Private key never leaves the KMS — verification delegated to KMS' : 'La clé privée ne quitte jamais le KMS — vérification déléguée au KMS'}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2">PublicKeyTrustRoot</h3>
        <CodeBlock code={PUBLIC_KEY_TRUST_ROOT} language="java" title="PublicKeyTrustRoot" />
        <Admonition type="warning" title={language === 'en' ? 'Not for production (key files)' : 'Pas pour la production (fichiers de clé)'}>
          {language === 'en'
            ? 'Loading long-term trust root public keys from local files is acceptable for development and testing. For production, use a key management service or load from a secrets manager. The public key itself is not sensitive — the concern is integrity and availability of the key store.'
            : 'Charger les clés publiques long terme depuis des fichiers locaux est acceptable pour le développement et les tests. Pour la production, utilisez un service de gestion de clés ou chargez depuis un gestionnaire de secrets. La clé publique elle-même n\'est pas sensible — la préoccupation est l\'intégrité et la disponibilité du key store.'}
        </Admonition>

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2 mt-6">DelegatedTrustRoot</h3>
        <CodeBlock code={DELEGATED_TRUST_ROOT} language="java" title="DelegatedTrustRoot — generic" />

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2 mt-6">
          {language === 'en' ? 'HashiCorp Vault Transit Engine' : 'HashiCorp Vault Transit Engine'}
        </h3>
        <CodeBlock code={VAULT_EXAMPLE} language="java" title="DelegatedTrustRoot — HashiCorp Vault" />

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2 mt-6">AWS KMS</h3>
        <CodeBlock code={AWS_KMS_EXAMPLE} language="java" title="DelegatedTrustRoot — AWS KMS" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Failure semantics' : 'Sémantique des échecs'}
        </h2>
        <div className="space-y-3">
          {(language === 'en' ? [
            { scenario: 'TrustRoot unavailable', behavior: 'The processor MUST fail closed. All pending verifications that require trust resolution are rejected. It MUST NOT fall back to accepting entries without trust resolution (Protocol V4 §13.4).' },
            { scenario: 'Issuer not found', behavior: 'resolve() throws a VeridotException. The verification pipeline rejects the entry with error V4101 (TRUST_RESOLUTION_FAILED).' },
            { scenario: 'Signature verification fails', behavior: 'The same rejection as issuer-not-found: V4101. No distinction is made in the error response to prevent oracle attacks.' },
          ] : [
            { scenario: 'TrustRoot indisponible', behavior: 'Le processeur DOIT échouer fermé. Toutes les vérifications en attente qui nécessitent la résolution de trust sont rejetées. Il NE DOIT PAS revenir à accepter des entrées sans résolution de trust (Protocole V4 §13.4).' },
            { scenario: 'Émetteur non trouvé', behavior: 'resolve() lance une VeridotException. Le pipeline de vérification rejette l\'entrée avec l\'erreur V4101 (TRUST_RESOLUTION_FAILED).' },
            { scenario: 'La vérification de signature échoue', behavior: 'Le même rejet que l\'émetteur introuvable : V4101. Aucune distinction n\'est faite dans la réponse d\'erreur pour prévenir les attaques par oracle.' },
          ]).map(item => (
            <div key={item.scenario} className="border border-slate-200 dark:border-slate-700 rounded-xl p-4 bg-white dark:bg-slate-900">
              <p className="font-semibold text-slate-900 dark:text-white text-sm mb-1">{item.scenario}</p>
              <p className="text-sm text-slate-600 dark:text-slate-400">{item.behavior}</p>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
