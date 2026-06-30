import { useApp } from '../../context/AppContext';
import { Admonition } from '../../components/Admonition';
import { CodeBlock } from '../../components/CodeBlock';

const GRAMMAR = `Envelope        := Magic ProtoVersion EntryType Flags
                    ScopeLen Scope KeyLen Key
                    Version Timestamp
                    IssuerLen Issuer
                    PayloadLen Payload
                    SigAlg SigLen Signature

Magic           := 0x56 0x44        ; "VD"
ProtoVersion    := 0x04
EntryType       := 0x01..0x06       ; see §4 registry
Flags           := 1*OCTET          ; bit 0: COMPACT_SIG; bits 1-7 MUST be zero
ScopeLen        := 2*OCTET          ; u16, big-endian
Scope           := ScopeGrammar
KeyLen          := 2*OCTET          ; u16, big-endian
Key             := *identifier-char  ; length = KeyLen
Version         := 8*OCTET          ; u64, big-endian
Timestamp       := 8*OCTET          ; i64, big-endian, ms since epoch
IssuerLen       := 2*OCTET          ; u16, big-endian
Issuer          := *identifier-char
PayloadLen      := 4*OCTET          ; u32, big-endian
Payload         := *TLVField
SigAlg          := 0x01 / 0x02 / 0x03 / 0x04
SigLen          := 2*OCTET          ; u16, big-endian
Signature       := *OCTET

ScopeGrammar    := "group:" identifier
                 / "site:" identifier
                 / "global"
identifier      := 1*125(identifier-char)`;

const STORAGE_KEY = `// Broker storage key derivation (§3.3)
storageKey = scope || 0x00 || entryType || 0x00 || key

// Examples:
// group:user-alice + 0x00 + 0x01 + 0x00 + mobile-v2
//   → scope=group:user-alice, type=KEY_EPOCH, key=mobile-v2
//
// global + 0x00 + 0x03 + 0x00 + ""
//   → scope=global, type=CONFIG (singleton, empty key)`;

const TLV_FORMAT = `// TLV field structure (§4.1)
// Each payload field is:
// [tag: 1 byte][length: 2 bytes big-endian][value: length bytes]

// Example: KEY_EPOCH payload parsing (§5.2)
// Tag 0x01 = alg    (u8)
// Tag 0x02 = epochId (u64 big-endian)
// Tag 0x03 = pk      (bytes, DER-encoded public key)
// Tag 0x04 = validFrom (i64 ms since epoch)
// Tag 0x05 = validUntil (i64 ms since epoch)
// Tag 0x06 = site    (string UTF-8, OPTIONAL)`;

export function WireFormatPage() {
  const { language } = useApp();

  const fields = [
    { field: 'magic', size: '2 bytes', type: 'fixed', desc: language === 'en' ? '0x56 0x44 ("VD") — protocol marker. MUST be validated first.' : '0x56 0x44 ("VD") — marqueur de protocole. DOIT être validé en premier.' },
    { field: 'protoVersion', size: '1 byte', type: 'u8', desc: language === 'en' ? 'MUST be 0x04. Any other value: reject with V4001.' : 'DOIT être 0x04. Toute autre valeur : rejeter avec V4001.' },
    { field: 'entryType', size: '1 byte', type: 'u8', desc: language === 'en' ? 'One of 0x01–0x06. Unregistered value: reject with V4002.' : 'Un de 0x01–0x06. Valeur non enregistrée : rejeter avec V4002.' },
    { field: 'flags', size: '1 byte', type: 'bitfield', desc: language === 'en' ? 'Bit 0: COMPACT_SIG (must be 1 iff sigAlg=Ed25519). Bits 1–7: reserved, MUST be zero.' : 'Bit 0 : COMPACT_SIG (doit être 1 ssi sigAlg=Ed25519). Bits 1–7 : réservés, DOIVENT être zéro.' },
    { field: 'scopeLen', size: '2 bytes', type: 'u16', desc: language === 'en' ? 'Length of scope in bytes. Range: 1–4096. Out of range: V4003.' : 'Longueur du scope en octets. Plage : 1–4096. Hors plage : V4003.' },
    { field: 'scope', size: 'variable', type: 'UTF-8', desc: language === 'en' ? 'Must match: "group:<id>", "site:<id>", or "global". Invalid grammar: V4006.' : 'Doit correspondre à : "group:<id>", "site:<id>", ou "global". Grammaire invalide : V4006.' },
    { field: 'keyLen', size: '2 bytes', type: 'u16', desc: language === 'en' ? 'Length of key in bytes. Range: 0–4096. Zero permitted for singleton entry types.' : 'Longueur de la clé en octets. Plage : 0–4096. Zéro permis pour les types d\'entrée singleton.' },
    { field: 'key', size: 'variable', type: 'UTF-8', desc: language === 'en' ? 'Entry key within scope. Excludes NUL and ASCII controls.' : 'Clé d\'entrée dans le scope. Exclut NUL et les contrôles ASCII.' },
    { field: 'version', size: '8 bytes', type: 'u64 BE', desc: language === 'en' ? 'Monotonic version. Must be > the recorded watermark. Minimum valid: 1.' : 'Version monotone. Doit être > le watermark enregistré. Minimum valide : 1.' },
    { field: 'timestamp', size: '8 bytes', type: 'i64 BE', desc: language === 'en' ? 'Wall-clock time in ms since epoch. Advisory only — MUST NOT be used for ordering decisions.' : 'Temps d\'horloge murale en ms depuis l\'époque. Consultatif uniquement — NE DOIT PAS être utilisé pour les décisions d\'ordre.' },
    { field: 'issuerLen', size: '2 bytes', type: 'u16', desc: language === 'en' ? 'Length of issuer in bytes. Range: 1–4096.' : 'Longueur de l\'émetteur en octets. Plage : 1–4096.' },
    { field: 'issuer', size: 'variable', type: 'UTF-8', desc: language === 'en' ? 'Long-term identifier resolved by the TrustRoot.' : 'Identifiant long terme résolu par la TrustRoot.' },
    { field: 'payloadLen', size: '4 bytes', type: 'u32 BE', desc: language === 'en' ? 'Length of payload in bytes. Range: 0–65536. Out of range: V4004.' : 'Longueur du payload en octets. Plage : 0–65536. Hors plage : V4004.' },
    { field: 'payload', size: 'variable', type: 'binary TLV', desc: language === 'en' ? 'Entry-type-specific fields (§5–§9). TLV format: [tag:1][len:2][value:len].' : 'Champs spécifiques au type d\'entrée (§5–§9). Format TLV : [tag:1][len:2][value:len].' },
    { field: 'sigAlg', size: '1 byte', type: 'u8', desc: language === 'en' ? '0x01=RSA-SHA256, 0x02=ECDSA-SHA256, 0x03=RSA-PSS, 0x04=Ed25519.' : '0x01=RSA-SHA256, 0x02=ECDSA-SHA256, 0x03=RSA-PSS, 0x04=Ed25519.' },
    { field: 'sigLen', size: '2 bytes', type: 'u16', desc: language === 'en' ? 'Length of signature in bytes.' : 'Longueur de la signature en octets.' },
    { field: 'signature', size: 'variable', type: 'binary', desc: language === 'en' ? 'Covers all bytes from magic through payload (§3.4). Signing excludes sigAlg, sigLen, signature itself.' : 'Couvre tous les octets de magic à payload (§3.4). La signature exclut sigAlg, sigLen, la signature elle-même.' },
  ];

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">
          {language === 'en' ? 'Protocol V4' : 'Protocole V4'}
        </p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Wire Format' : 'Format binaire'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Every Veridot V4 entry is encoded as a single binary envelope. All multi-byte integers are big-endian. There is no implicit padding or alignment between fields.'
            : 'Chaque entrée Veridot V4 est encodée comme une seule enveloppe binaire. Tous les entiers multi-octets sont big-endian. Il n\'y a pas de rembourrage implicite ni d\'alignement entre les champs.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Envelope structure (§3.1)' : 'Structure de l\'enveloppe (§3.1)'}
        </h2>
        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800/50">
                {(language === 'en' ? ['Field', 'Size', 'Type', 'Description'] : ['Champ', 'Taille', 'Type', 'Description']).map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
              {fields.map((f, i) => (
                <tr key={f.field} className={i % 2 === 0 ? 'bg-white dark:bg-slate-900' : 'bg-slate-50/50 dark:bg-slate-900/50'}>
                  <td className="px-4 py-2.5 font-mono text-violet-700 dark:text-violet-300 text-sm font-medium">{f.field}</td>
                  <td className="px-4 py-2.5 font-mono text-xs text-slate-500 whitespace-nowrap">{f.size}</td>
                  <td className="px-4 py-2.5 font-mono text-xs text-slate-500">{f.type}</td>
                  <td className="px-4 py-2.5 text-slate-600 dark:text-slate-400 text-sm">{f.desc}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Formal grammar (Appendix A)' : 'Grammaire formelle (Annexe A)'}
        </h2>
        <CodeBlock code={GRAMMAR} language="text" title="Envelope ABNF grammar" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Canonical signing bytes (§3.4)' : 'Octets de signature canoniques (§3.4)'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 mb-4">
          {language === 'en'
            ? 'The signature covers every byte of the envelope from magic through payload — i.e., every byte that precedes sigAlg. This construction prevents relocating a valid signature to a different scope, key, version, or payload.'
            : 'La signature couvre chaque octet de l\'enveloppe de magic à payload — c\'est-à-dire chaque octet précédant sigAlg. Cette construction empêche de déplacer une signature valide vers un scope, une clé, une version ou un payload différent.'}
        </p>
        <Admonition type="security" title={language === 'en' ? 'No field excluded from signing' : 'Aucun champ exclu de la signature'}>
          {language === 'en'
            ? 'scope, key, version, issuer, and payload are all part of the signed region. This makes it impossible for an attacker to: copy a valid envelope to a different scope (scope injection), replay an old version (monotonic version invariant §11.1 adds a second defense), or substitute a different payload.'
            : 'scope, key, version, issuer et payload font tous partie de la région signée. Cela rend impossible pour un attaquant de : copier une enveloppe valide vers un scope différent (injection de scope), rejouer une ancienne version (l\'invariant de version monotone §11.1 ajoute une deuxième défense), ou substituer un payload différent.'}
        </Admonition>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Broker storage key (§3.3)' : 'Clé de stockage broker (§3.3)'}
        </h2>
        <CodeBlock code={STORAGE_KEY} language="text" title="EntryId → storage key derivation" showLineNumbers={false} />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'TLV payload encoding (§4.1)' : 'Encodage TLV du payload (§4.1)'}
        </h2>
        <CodeBlock code={TLV_FORMAT} language="text" title="TLV format" showLineNumbers={false} />
        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700 mt-3">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800/50">
                {(language === 'en' ? ['TLV Rule', 'Behavior on violation'] : ['Règle TLV', 'Comportement en cas de violation']).map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800 bg-white dark:bg-slate-900">
              {[
                { rule: language === 'en' ? 'Tag 0x00 appears' : 'Tag 0x00 apparaît', behavior: 'Reject with V4007' },
                { rule: language === 'en' ? 'REQUIRED field missing' : 'Champ REQUIS manquant', behavior: 'Reject with V4007' },
                { rule: language === 'en' ? 'Tag appears more than once' : 'Tag apparaît plus d\'une fois', behavior: 'Reject with V4007' },
                { rule: language === 'en' ? 'Unknown tag' : 'Tag inconnu', behavior: language === 'en' ? 'Silently ignore (forward compatibility)' : 'Ignorer silencieusement (compatibilité ascendante)' },
                { rule: language === 'en' ? 'Tags 0xF0–0xFF' : 'Tags 0xF0–0xFF', behavior: language === 'en' ? 'Silently ignore if unknown (reserved for future extension)' : 'Ignorer silencieusement si inconnu (réservé pour extension future)' },
              ].map(r => (
                <tr key={r.rule}>
                  <td className="px-4 py-3 text-slate-700 dark:text-slate-300">{r.rule}</td>
                  <td className="px-4 py-3 font-mono text-sm text-red-600 dark:text-red-400">{r.behavior}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}
