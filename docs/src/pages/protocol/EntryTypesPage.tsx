import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const TLV_FORMAT = `Tag (1 byte) | Length (2 bytes, big-endian) | Value (variable bytes)`;

export function EntryTypesPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">Protocol V4</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Protocol Entry Types' : 'Types d\'Entrée de Protocole'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Veridot specifies six core entry types. Each entry encapsulates its specific payload in a Tag-Length-Value (TLV) byte array.'
            : 'Veridot répertorie six types d\'entrée distincts. Chaque type stocke sa charge utile dans un format Tag-Length-Value (TLV).'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-2">
          {language === 'en' ? 'TLV Encoding Layout' : 'Format de l\'encodage TLV'}
        </h2>
        <CodeBlock code={TLV_FORMAT} language="text" showLineNumbers={false} />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Registered Entry Types' : 'Registre des Types d\'Entrée'}
        </h2>
        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-slate-50 dark:bg-slate-800/50">
                {['Code', 'Type Name', 'Singleton?', language === 'en' ? 'Description' : 'Description'].map(h => (
                  <th key={h} className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800 bg-white dark:bg-slate-900">
              {[
                { code: '0x01', name: 'KEY_EPOCH', singleton: 'No (by session key)', desc: language === 'en' ? 'Distributes ephemeral public key material and Temporal Validity' : 'Distribue les clés publiques éphémères et la validité temporelle' },
                { code: '0x02', name: 'CAPABILITY', singleton: 'No (by subject)', desc: language === 'en' ? 'Authorizes delegated signers to act within scopes' : 'Autorise les signataires délégués à écrire dans des scopes' },
                { code: '0x03', name: 'CONFIG', singleton: 'Yes (key is empty)', desc: language === 'en' ? 'Scope-level session capacity limits & eviction policies' : 'Définit les limites de sessions et politiques d\'éviction' },
                { code: '0x04', name: 'LIVENESS', singleton: 'Yes per session', desc: language === 'en' ? 'Positive-proof session state (ACTIVE or REVOKED)' : 'Preuve positive de l\'état de la session (ACTIVE ou REVOKED)' },
                { code: '0x05', name: 'FENCE', singleton: 'Yes (key is empty)', desc: language === 'en' ? 'Monotonic counters ordering concurrent mutations' : 'Compteur monotone ordonnant les mutations concurrentes' },
                { code: '0x06', name: 'SNAPSHOT_MARKER', singleton: 'Yes (key is empty)', desc: language === 'en' ? 'Bounds the reconciliation staleness window' : 'Borne la fenêtre de détection de retard lors de la réconciliation' },
              ].map(row => (
                <tr key={row.code}>
                  <td className="px-4 py-3 font-mono text-violet-600 font-semibold">{row.code}</td>
                  <td className="px-4 py-3 font-mono font-medium">{row.name}</td>
                  <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{row.singleton}</td>
                  <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{row.desc}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <Admonition type="warning" title={language === 'en' ? 'Strict Verification' : 'Vérification Stricte'}>
        {language === 'en'
          ? 'Conforming processors MUST reject any unknown entry codes with error V4002 rather than ignore them, preventing unauthenticated states from slipping through.'
          : 'Les processeurs conformes DOIVENT rejeter tout code d\'entrée inconnu avec l\'erreur V4002 plutôt que de l\'ignorer.'}
      </Admonition>
    </div>
  );
}
