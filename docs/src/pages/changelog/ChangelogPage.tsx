import { useApp } from '../../context/AppContext';

export function ChangelogPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">Reference</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">Changelog</h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Track release history and protocol updates for the Veridot framework.'
            : 'Suivez l\'historique des versions et les évolutions du protocole Veridot.'}
        </p>
      </div>

      <div className="space-y-8 border-l border-slate-200 dark:border-slate-800 pl-6 ml-4">
        <div className="relative">
          <span className="absolute -left-[31px] top-1 h-3 w-3 rounded-full bg-violet-600 border-2 border-white dark:border-slate-950" />
          <h2 className="text-xl font-bold text-slate-900 dark:text-white">v4.0.1 (2026-07-01)</h2>
          <p className="text-xs text-slate-400 mt-1">Security Hardening & Performance Fixes</p>
          <ul className="list-disc pl-5 mt-3 text-sm text-slate-600 dark:text-slate-400 space-y-1">
            <li><strong>RSA-OAEP Migration</strong>: Replaced textbook RSA encryption with RSA-OAEP (SHA-256 / MGF1 padding) for wrapping symmetric keys.</li>
            <li><strong>Hardened ECIES Envelope</strong>: Replaced ECIES AES-ECB with AES-GCM (AEAD) using random 12-byte IVs and HKDF-SHA256 for key derivation.</li>
            <li><strong>Deterministic JWT Claim Ordering</strong>: Claims in headers and payloads are sorted alphabetically and serialized using LinkedHashMap for canonical output.</li>
            <li><strong>Timing-safe Warning logs</strong>: SignatureVerifier warns on timing-unsafe signature verifications (RSA, ECDSA), urging transition to Ed25519.</li>
            <li><strong>Aggressive Capability Revocations</strong>: Reduced positive cache TTL default to 10 seconds to shorten revoked capability propagation window.</li>
          </ul>
        </div>

        <div className="relative">
          <span className="absolute -left-[31px] top-1 h-3 w-3 rounded-full bg-slate-300 dark:bg-slate-700 border-2 border-white dark:border-slate-950" />
          <h2 className="text-xl font-bold text-slate-900 dark:text-white">v4.0.0 (2026-06-28)</h2>
          <p className="text-xs text-slate-400 mt-1">Protocol V4 Standard Release</p>
          <ul className="list-disc pl-5 mt-3 text-sm text-slate-600 dark:text-slate-400 space-y-1">
            <li><strong>Decoupled KMS Architecture</strong>: Isolates runtime verification from long-term KMS keys.</li>
            <li><strong>Ed25519 Support</strong>: Introduces NIST-compliant constant-time signature algorithms (code `0x04`).</li>
            <li><strong>Fenced Quotas</strong>: Concurrent quota limits managed using FENCE token counters.</li>
            <li><strong>Reconciliation</strong>: Snapshot markers verify cache complete-state sync.</li>
          </ul>
        </div>

        <div className="relative">
          <span className="absolute -left-[31px] top-1 h-3 w-3 rounded-full bg-slate-300 dark:bg-slate-700 border-2 border-white dark:border-slate-950" />
          <h2 className="text-xl font-bold text-slate-900 dark:text-white">v3.0.0</h2>
          <p className="text-xs text-slate-400 mt-1">Protocol V3 Legacy</p>
          <ul className="list-disc pl-5 mt-3 text-sm text-slate-600 dark:text-slate-400 space-y-1">
            <li>Introduced asymmetric ephemeral key-pair verification.</li>
            <li>Added Kafka broker support for metadata broadcasts.</li>
            <li>Configurable session limits.</li>
          </ul>
        </div>
      </div>
    </div>
  );
}
