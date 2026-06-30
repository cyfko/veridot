import { useApp } from '../../context/AppContext';
import { Admonition } from '../../components/Admonition';

export function LivenessPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">Protocol V4</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Liveness & Revocation' : 'Liveness & Révocation'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Verify session validity using Veridots positive-proof liveness model. Validity is established exclusively by active, fresh attestations.'
            : 'Validez les sessions à l\'aide du modèle de liveness positif de Veridot.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Default-Deny Semantics' : 'Semantique d\'Interdiction par Défaut'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 leading-relaxed mb-3">
          {language === 'en'
            ? 'In Veridot, a session is considered active if and only if a valid LIVENESS entry exists with status ACTIVE (0x01) and the current timestamp is before validUntil. If the entry is absent, expired, or carries status REVOKED (0x02), verification fails immediately.'
            : 'Une session est active uniquement si une entrée LIVENESS existe avec le statut ACTIVE (0x01) et que l\'horodatage actuel est inférieur à validUntil. L\'absence, l\'expiration ou le statut REVOKED (0x02) provoque un échec de validation.'}
        </p>
        <p className="text-slate-600 dark:text-slate-400 leading-relaxed">
          {language === 'en'
            ? 'This design ensures that if a network partition isolates a Verifier microservice from the Broker, the system fails closed. It defaults to rejecting authentication, preventing unauthorized access.'
            : 'Ce choix garantit que si une coupure réseau isole le vérificateur du courtier, le système refuse l\'accès (fail-closed) par sécurité.'}
        </p>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Liveness Attestation Lifecycle' : 'Cycle de vie de l\'Attestation de Liveness'}
        </h2>
        <ul className="list-decimal pl-5 space-y-2 text-sm text-slate-600 dark:text-slate-400">
          <li><strong>Issuance</strong>: The Issuer publishes the initial LIVENESS(ACTIVE) envelope along with the token.</li>
          <li><strong>Renewal Loop</strong>: In the background, the Issuer publishing service periodically issues renewed envelopes (with incremented versions) to extend the validity window.</li>
          <li><strong>Verification</strong>: Verifiers query the local RocksDB or DB cache. If the current time exceeds `validUntil`, the session is treated as revoked.</li>
          <li><strong>Explicit Revocation</strong>: An explicit LIVENESS(REVOKED) envelope is published, immediately overriding any previously cached active state.</li>
        </ul>
      </section>

      <Admonition type="info" title={language === 'en' ? 'Renewal Recommendation' : 'Recommandation de Renouvellement'}>
        {language === 'en'
          ? 'Issuers should publish renewed attestations within the last 20% of the active validity window. This leaves sufficient margin to accommodate propagation delays and transient network issues.'
          : 'Les émetteurs doivent publier le renouvellement dans les 20 derniers pourcents de la fenêtre de validité active pour compenser les délais de propagation.'}
      </Admonition>
    </div>
  );
}
