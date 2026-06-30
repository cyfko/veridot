import { useApp } from '../../context/AppContext';
import { Mermaid } from '../../components/Mermaid';
import { Admonition } from '../../components/Admonition';

const PROTOCOL_OVERVIEW = `graph TB
    subgraph "Protocol V4 Entry Types"
        KE[KEY_EPOCH 0x01\nEphemeral public key\n+ temporal window]
        CAP[CAPABILITY 0x02\nIssuer scope grant\n+ delegation chain]
        CFG[CONFIG 0x03\nSession capacity\n+ eviction policy]
        LIV[LIVENESS 0x04\nACTIVE or REVOKED\nper session]
        FEN[FENCE 0x05\nCapacity mutation\nordering token]
        SNP[SNAPSHOT_MARKER 0x06\nReconciliation marker]
    end

    subgraph "Envelope (every entry type)"
        ENV[magic VD + protoVersion 0x04\nentryType + flags + scope\nkey + version + timestamp\nissuer + payload TLV\nsigAlg + signature]
    end

    KE --> ENV
    CAP --> ENV
    CFG --> ENV
    LIV --> ENV
    FEN --> ENV
    SNP --> ENV

    style ENV fill:#ede9fe,stroke:#7c3aed
    style KE fill:#dcfce7,stroke:#16a34a
    style LIV fill:#dbeafe,stroke:#2563eb
    style CAP fill:#fef3c7,stroke:#d97706`;

const LIFECYCLE = `stateDiagram-v2
    [*] --> KEY_EPOCH_PUBLISHED: sign() called
    KEY_EPOCH_PUBLISHED --> LIVENESS_ACTIVE: LIVENESS=ACTIVE published
    LIVENESS_ACTIVE --> VERIFIED: verify() called
    VERIFIED --> LIVENESS_ACTIVE: renewal loop
    LIVENESS_ACTIVE --> LIVENESS_REVOKED: revoke() called
    LIVENESS_REVOKED --> [*]: session terminated
    KEY_EPOCH_PUBLISHED --> KEY_EPOCH_EXPIRED: validUntil reached
    KEY_EPOCH_EXPIRED --> [*]: no active liveness`;

export function ProtocolOverviewPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">
          {language === 'en' ? 'Protocol V4' : 'Protocole V4'}
        </p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Protocol V4 Overview' : 'Vue d\'ensemble du Protocole V4'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'The Veridot Protocol V4 defines a binary, self-describing message format enabling distributed verification of signed objects without shared secrets.'
            : 'Le Protocole Veridot V4 définit un format de message binaire auto-descriptif permettant la vérification distribuée d\'objets signés sans secrets partagés.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Entry type registry' : 'Registre des types d\'entrée'}
        </h2>
        <Mermaid chart={PROTOCOL_OVERVIEW} caption={language === 'en' ? 'All 6 Protocol V4 entry types and the common envelope' : 'Les 6 types d\'entrée du Protocole V4 et l\'enveloppe commune'} />

        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700 mt-4">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800/50">
                {(language === 'en' ? ['Code', 'Name', 'Singleton?', 'Purpose', '§'] : ['Code', 'Nom', 'Singleton ?', 'Objectif', '§']).map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
              {[
                { code: '0x01', name: 'KEY_EPOCH', single: language === 'en' ? 'No (one per session)' : 'Non (un par session)', purpose: language === 'en' ? 'Distributes ephemeral public key, algorithm, and validity window' : 'Distribue la clé publique éphémère, l\'algorithme et la fenêtre de validité', section: '§5' },
                { code: '0x02', name: 'CAPABILITY', single: language === 'en' ? 'No (one per issuer/grant)' : 'Non (un par émetteur/autorisation)', purpose: language === 'en' ? 'Authorizes an issuer to publish entries within scope patterns' : 'Autorise un émetteur à publier des entrées dans des patterns de scope', section: '§6' },
                { code: '0x03', name: 'CONFIG', single: language === 'en' ? 'Yes (key = empty)' : 'Oui (clé = vide)', purpose: language === 'en' ? 'Signed session capacity and eviction configuration for a scope' : 'Configuration signée de capacité de session et d\'éviction pour un scope', section: '§7' },
                { code: '0x04', name: 'LIVENESS', single: language === 'en' ? 'Yes per session (key = sessionKey)' : 'Oui par session (clé = sessionKey)', purpose: language === 'en' ? 'ACTIVE or REVOKED attestation for a specific session' : 'Attestation ACTIVE ou REVOKED pour une session spécifique', section: '§8' },
                { code: '0x05', name: 'FENCE', single: language === 'en' ? 'Yes (key = empty)' : 'Oui (clé = vide)', purpose: language === 'en' ? 'Monotonically increasing counter to order capacity mutations' : 'Compteur monotone croissant pour ordonner les mutations de capacité', section: '§9' },
                { code: '0x06', name: 'SNAPSHOT_MARKER', single: language === 'en' ? 'Yes (key = empty)' : 'Oui (clé = vide)', purpose: language === 'en' ? 'Marks completion of a full-scope snapshot for reconciliation' : 'Marque la complétion d\'un snapshot de scope complet pour la réconciliation', section: '§11.4' },
              ].map((row, i) => (
                <tr key={row.code} className={i % 2 === 0 ? 'bg-white dark:bg-slate-900' : 'bg-slate-50/50 dark:bg-slate-900/50'}>
                  <td className="px-4 py-3 font-mono text-violet-700 dark:text-violet-300 font-bold">{row.code}</td>
                  <td className="px-4 py-3 font-mono font-semibold text-slate-800 dark:text-slate-200">{row.name}</td>
                  <td className="px-4 py-3 text-slate-500 dark:text-slate-400 text-xs">{row.single}</td>
                  <td className="px-4 py-3 text-slate-600 dark:text-slate-400 text-sm">{row.purpose}</td>
                  <td className="px-4 py-3 font-mono text-xs text-slate-400">{row.section}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Session lifecycle' : 'Cycle de vie d\'une session'}
        </h2>
        <Mermaid chart={LIFECYCLE} caption={language === 'en' ? 'Protocol V4 session state machine' : 'Machine d\'état de session Protocole V4'} />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Design principles (§1.3)' : 'Principes de conception (§1.3)'}
        </h2>
        <div className="space-y-3">
          {(language === 'en' ? [
            { name: 'Deny by default', ref: '§1.3', desc: 'Any entry that is malformed, unauthorized, stale, or for which authoritative state cannot be positively established MUST be rejected. Absence of information MUST NOT be interpreted as a permissive state.' },
            { name: 'Structural authorization', ref: '§1.3, §6', desc: 'Authorization MUST be established by a verifiable cryptographic CAPABILITY entry. No application-defined callback, no default grant.' },
            { name: 'Monotonic state', ref: '§11', desc: 'For any given scope and entry type, state MUST only move forward. No protocol operation permits a conforming processor to regress to an earlier known state.' },
            { name: 'Positive liveness proof', ref: '§8.3', desc: 'A session is valid only when a fresh, signed ACTIVE attestation is held. Expiration, absence, or invalidity all produce rejection.' },
            { name: 'Uniform envelope', ref: '§3', desc: 'All information uses one canonical signed envelope and one verification pipeline. No entry type may bypass cryptographic verification.' },
            { name: 'Availability over consistency for non-authoritative reads', ref: '§1.3', desc: 'Broker reads MAY be eventually consistent. Authoritative decisions (revocation, fencing) rely on monotonicity and reconciliation, not single-read consistency.' },
          ] : [
            { name: 'Refus par défaut', ref: '§1.3', desc: 'Toute entrée malformée, non autorisée, périmée ou pour laquelle l\'état autoritaire ne peut être établi positivement DOIT être rejetée. L\'absence d\'information NE DOIT PAS être interprétée comme un état permissif.' },
            { name: 'Autorisation structurelle', ref: '§1.3, §6', desc: 'L\'autorisation DOIT être établie par une entrée CAPABILITY cryptographique vérifiable. Pas de callback défini par l\'application, pas d\'autorisation par défaut.' },
            { name: 'État monotone', ref: '§11', desc: 'Pour tout scope et type d\'entrée donnés, l\'état ne peut qu\'avancer. Aucune opération de protocole ne permet à un processeur conforme de régresser vers un état antérieur connu.' },
            { name: 'Preuve positive de liveness', ref: '§8.3', desc: 'Une session est valide uniquement quand une attestation ACTIVE fraîche et signée est détenue. L\'expiration, l\'absence ou l\'invalidité produisent toutes un rejet.' },
            { name: 'Enveloppe uniforme', ref: '§3', desc: 'Toutes les informations utilisent une enveloppe signée canonique et un pipeline de vérification unique. Aucun type d\'entrée ne peut contourner la vérification cryptographique.' },
          ]).map(p => (
            <div key={p.name} className="border-l-4 border-violet-400 pl-4 py-2 bg-white dark:bg-slate-900 rounded-r-lg border border-slate-100 dark:border-slate-800">
              <div className="flex items-center gap-2 mb-1">
                <p className="font-semibold text-slate-900 dark:text-white text-sm">{p.name}</p>
                <span className="text-xs text-slate-400 font-mono">{p.ref}</span>
              </div>
              <p className="text-sm text-slate-600 dark:text-slate-400">{p.desc}</p>
            </div>
          ))}
        </div>
      </section>

      <Admonition type="info" title={language === 'en' ? 'Multi-version coexistence (§14.3)' : 'Coexistence multi-version (§14.3)'}>
        {language === 'en'
          ? 'Multiple major protocol versions MAY coexist on the same broker transport, distinguished by the magic bytes (0x56 0x44) and protoVersion at the start of every envelope. A processor encountering an unsupported protoVersion MUST reject it with V4001 rather than attempt best-effort interpretation.'
          : 'Plusieurs versions majeures du protocole PEUVENT coexister sur le même transport broker, distinguées par les octets magic (0x56 0x44) et protoVersion au début de chaque enveloppe. Un processeur rencontrant un protoVersion non supporté DOIT le rejeter avec V4001 plutôt que tenter une interprétation au mieux.'}
      </Admonition>
    </div>
  );
}
