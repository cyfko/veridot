import { useApp } from '../../context/AppContext';

export function ErrorCodesPage() {
  const { language } = useApp();

  const errorCodes = [
    { code: 'V4001', name: 'INVALID_ENVELOPE', category: language === 'en' ? 'Envelope' : 'Enveloppe', desc: language === 'en' ? 'Magic bytes (0x56 0x44) or protoVersion (0x04) does not match. First check performed — envelope is rejected without parsing further.' : 'Les octets magic (0x56 0x44) ou protoVersion (0x04) ne correspondent pas. Premier contrôle effectué — l\'enveloppe est rejetée sans analyse ultérieure.' },
    { code: 'V4002', name: 'UNREGISTERED_ENTRY_TYPE', category: language === 'en' ? 'Envelope' : 'Enveloppe', desc: language === 'en' ? 'entryType is not in the registry (0x01–0x06). Codes 0x07–0xFF are reserved and MUST be rejected.' : 'entryType n\'est pas dans le registre (0x01–0x06). Les codes 0x07–0xFF sont réservés et DOIVENT être rejetés.' },
    { code: 'V4003', name: 'INVALID_IDENTIFIER_LENGTH', category: language === 'en' ? 'Envelope' : 'Enveloppe', desc: language === 'en' ? 'scopeLen, keyLen, or issuerLen is outside permitted bounds (scopeLen/issuerLen: 1–4096; keyLen: 0–4096).' : 'scopeLen, keyLen ou issuerLen est en dehors des limites permises (scopeLen/issuerLen : 1–4096 ; keyLen : 0–4096).' },
    { code: 'V4004', name: 'INVALID_PAYLOAD_LENGTH', category: language === 'en' ? 'Envelope' : 'Enveloppe', desc: language === 'en' ? 'payloadLen is outside permitted bounds (0–65536).' : 'payloadLen est en dehors des limites permises (0–65536).' },
    { code: 'V4005', name: 'RESERVED_FLAG_SET', category: language === 'en' ? 'Envelope' : 'Enveloppe', desc: language === 'en' ? 'A reserved bit in flags (bits 1–7) was set, or the COMPACT_SIG bit (bit 0) is inconsistent with sigAlg (must be 1 iff Ed25519).' : 'Un bit réservé dans flags (bits 1–7) est défini, ou le bit COMPACT_SIG (bit 0) est incohérent avec sigAlg (doit être 1 ssi Ed25519).' },
    { code: 'V4006', name: 'INVALID_SCOPE_GRAMMAR', category: language === 'en' ? 'Envelope' : 'Enveloppe', desc: language === 'en' ? 'scope does not match the grammar: "group:<id>", "site:<id>", or "global".' : 'scope ne correspond pas à la grammaire : "group:<id>", "site:<id>", ou "global".' },
    { code: 'V4007', name: 'MALFORMED_PAYLOAD', category: language === 'en' ? 'Payload' : 'Payload', desc: language === 'en' ? 'Payload TLV is malformed: tag 0x00 used, REQUIRED field missing, duplicate tag.' : 'Le TLV du payload est malformé : tag 0x00 utilisé, champ REQUIS manquant, tag dupliqué.' },
    { code: 'V4101', name: 'TRUST_RESOLUTION_FAILED', category: language === 'en' ? 'Trust' : 'Trust', desc: language === 'en' ? 'issuer could not be resolved by the TrustRoot, or signature verification failed.' : 'issuer n\'a pas pu être résolu par la TrustRoot, ou la vérification de signature a échoué.' },
    { code: 'V4102', name: 'CAPABILITY_NOT_FOUND', category: language === 'en' ? 'Authorization' : 'Autorisation', desc: language === 'en' ? 'No valid CAPABILITY entry authorizes the issuer for the entry\'s scope.' : 'Aucune entrée CAPABILITY valide n\'autorise l\'émetteur pour le scope de l\'entrée.' },
    { code: 'V4103', name: 'CAPABILITY_EXPIRED', category: language === 'en' ? 'Authorization' : 'Autorisation', desc: language === 'en' ? 'A CAPABILITY entry was found but its validUntil has passed (now ≥ validUntil).' : 'Une entrée CAPABILITY a été trouvée mais son validUntil est passé (now ≥ validUntil).' },
    { code: 'V4104', name: 'DELEGATION_DEPTH_EXCEEDED', category: language === 'en' ? 'Authorization' : 'Autorisation', desc: language === 'en' ? 'The capability delegation chain exceeds maxDelegationDepth.' : 'La chaîne de délégation de capacité dépasse maxDelegationDepth.' },
    { code: 'V4201', name: 'STALE_VERSION', category: language === 'en' ? 'Ordering' : 'Ordre', desc: language === 'en' ? 'The incoming entry\'s version is not strictly greater than the recorded watermark, or version = 0.' : 'La version de l\'entrée entrante n\'est pas strictement supérieure au watermark enregistré, ou version = 0.' },
    { code: 'V4202', name: 'LIVENESS_NOT_ESTABLISHED', category: language === 'en' ? 'Liveness' : 'Liveness', desc: language === 'en' ? 'No fresh, valid ACTIVE LIVENESS entry is available for the target session. Covers: absence, REVOKED status, or expired attestation.' : 'Aucune entrée LIVENESS ACTIVE fraîche et valide n\'est disponible pour la session cible. Couvre : absence, statut REVOKED ou attestation expirée.' },
    { code: 'V4203', name: 'KEY_EPOCH_EXPIRED', category: language === 'en' ? 'Temporal' : 'Temporel', desc: language === 'en' ? 'Current time is outside [validFrom - 5min, validUntil) for the referenced KEY_EPOCH.' : 'L\'heure actuelle est en dehors de [validFrom - 5min, validUntil) pour le KEY_EPOCH référencé.' },
    { code: 'V4204', name: 'SIGALG_KEY_MISMATCH', category: language === 'en' ? 'Cryptographic' : 'Cryptographique', desc: language === 'en' ? 'sigAlg value is unknown, or is inconsistent with the key type resolved from the TrustRoot. Also covers JWT alg header mismatch.' : 'La valeur sigAlg est inconnue, ou incohérente avec le type de clé résolu depuis la TrustRoot. Couvre aussi l\'inadéquation de l\'en-tête alg JWT.' },
    { code: 'V4301', name: 'FENCE_TOKEN_STALE', category: language === 'en' ? 'Capacity' : 'Capacité', desc: language === 'en' ? 'fenceCounter is not strictly greater than the recorded watermark for the scope. Another processor already advanced the fence.' : 'fenceCounter n\'est pas strictement supérieur au watermark enregistré pour le scope. Un autre processeur a déjà avancé le fence.' },
    { code: 'V4302', name: 'CAPACITY_EXCEEDED', category: language === 'en' ? 'Capacity' : 'Capacité', desc: language === 'en' ? 'maxSessions reached under pol = REJECT. No new session can be created for this group.' : 'maxSessions atteint sous pol = REJECT. Aucune nouvelle session ne peut être créée pour ce groupe.' },
    { code: 'V4401', name: 'TRANSPORT_UNAVAILABLE', category: language === 'en' ? 'Transport' : 'Transport', desc: language === 'en' ? 'Broker read or write failure. Treated as rejection (fail-closed). Logged separately from trust/liveness errors.' : 'Échec de lecture ou d\'écriture du broker. Traité comme un rejet (fail-closed). Journalisé séparément des erreurs de trust/liveness.' },
  ];

  const categoryColors: Record<string, string> = {
    'Envelope': 'bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300',
    'Enveloppe': 'bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300',
    'Payload': 'bg-slate-100 dark:bg-slate-700 text-slate-700 dark:text-slate-300',
    'Trust': 'bg-violet-100 dark:bg-violet-900/30 text-violet-700 dark:text-violet-300',
    'Authorization': 'bg-amber-100 dark:bg-amber-900/30 text-amber-700 dark:text-amber-300',
    'Autorisation': 'bg-amber-100 dark:bg-amber-900/30 text-amber-700 dark:text-amber-300',
    'Ordering': 'bg-orange-100 dark:bg-orange-900/30 text-orange-700 dark:text-orange-300',
    'Ordre': 'bg-orange-100 dark:bg-orange-900/30 text-orange-700 dark:text-orange-300',
    'Liveness': 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-300',
    'Temporal': 'bg-cyan-100 dark:bg-cyan-900/30 text-cyan-700 dark:text-cyan-300',
    'Temporel': 'bg-cyan-100 dark:bg-cyan-900/30 text-cyan-700 dark:text-cyan-300',
    'Cryptographic': 'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-300',
    'Cryptographique': 'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-300',
    'Capacity': 'bg-pink-100 dark:bg-pink-900/30 text-pink-700 dark:text-pink-300',
    'Capacité': 'bg-pink-100 dark:bg-pink-900/30 text-pink-700 dark:text-pink-300',
    'Transport': 'bg-gray-100 dark:bg-gray-800 text-gray-700 dark:text-gray-300',
  };

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">
          {language === 'en' ? 'Security' : 'Sécurité'}
        </p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Error Codes (Appendix B)' : 'Codes d\'erreur (Annexe B)'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Complete reference for all Protocol V4 error codes. Every rejection must be logged with its corresponding error code and the EntryId involved (§12.1).'
            : 'Référence complète pour tous les codes d\'erreur du Protocole V4. Chaque rejet doit être journalisé avec son code d\'erreur correspondant et l\'EntryId impliqué (§12.1).'}
        </p>
      </div>

      <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-slate-50 dark:bg-slate-800/50">
              {(language === 'en' ? ['Code', 'Name', 'Category', 'Description'] : ['Code', 'Nom', 'Catégorie', 'Description']).map(h => (
                <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
            {errorCodes.map((ec, i) => (
              <tr key={ec.code} className={i % 2 === 0 ? 'bg-white dark:bg-slate-900' : 'bg-slate-50/50 dark:bg-slate-900/50'}>
                <td className="px-4 py-3 font-mono text-violet-700 dark:text-violet-300 font-bold text-sm whitespace-nowrap">{ec.code}</td>
                <td className="px-4 py-3 font-mono text-sm text-slate-700 dark:text-slate-300 whitespace-nowrap">{ec.name}</td>
                <td className="px-4 py-3">
                  <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${categoryColors[ec.category] || ''}`}>
                    {ec.category}
                  </span>
                </td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-400 text-sm">{ec.desc}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
