import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const INTERFACE_CODE = `public interface Broker {
    CompletableFuture<Void> put(byte[] storageKey, byte[] envelopeBytes);
    byte[] get(byte[] storageKey);
    List<BrokerEntry> snapshot(Scope scope);
    void putLocal(byte[] storageKey, byte[] envelopeBytes);
}`;

const ENTRY_CODE = `public record BrokerEntry(byte[] storageKey, byte[] envelopeBytes) {
    // Encapsulates a raw key-value pair retrieved during scope snapshotting
}`;

const IMPLEMENTATION_CODE = `import io.github.cyfko.veridot.core.Broker;
import io.github.cyfko.veridot.core.impl.Scope;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.ArrayList;

public class InMemoryBroker implements Broker {
    private final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Void> put(byte[] storageKey, byte[] envelopeBytes) {
        String hexKey = bytesToHex(storageKey);
        if (envelopeBytes.length == 0) {
            store.remove(hexKey);
        } else {
            store.put(hexKey, envelopeBytes);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public byte[] get(byte[] storageKey) {
        return store.get(bytesToHex(storageKey));
    }

    @Override
    public List<BrokerEntry> snapshot(Scope scope) {
        List<BrokerEntry> list = new ArrayList<>();
        byte[] scopeBytes = scope.value().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        
        // Scan store and select entries whose storageKey starts with the scope namespace
        store.forEach((hex, val) -> {
            byte[] key = hexToBytes(hex);
            if (startsWith(key, scopeBytes)) {
                list.add(new BrokerEntry(key, val));
            }
        });
        return list;
    }

    @Override
    public void putLocal(byte[] storageKey, byte[] envelopeBytes) {
        // Cache entry locally to bypass read-after-write latencies
        if (storageKey != null && envelopeBytes != null) {
            store.put(bytesToHex(storageKey), envelopeBytes);
        }
    }
}`;

export function BrokerPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">API Reference</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-1">Broker</h1>
        <p className="text-sm font-mono text-slate-500 dark:text-slate-400 mb-4">io.github.cyfko.veridot.core</p>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'The Broker interface defines the storage contract for Veridot. It is responsible for transporting and persisting raw signed envelopes. Veridot is transport-agnostic and relies on the broker to propagate states across services.'
            : 'L\'interface Broker définit le contrat de stockage pour Veridot. Elle est responsable du transport et de la persistance des enveloppes brutes signées. Veridot est agnostique du transport.'}
        </p>
      </div>

      <div className="rounded-xl border border-slate-200 dark:border-slate-700 overflow-hidden">
        <div className="px-5 py-3 bg-slate-50 dark:bg-slate-800/50 border-b border-slate-200 dark:border-slate-700">
          <span className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">
            {language === 'en' ? 'Interface declaration' : 'Déclaration de l\'interface'}
          </span>
        </div>
        <CodeBlock code={INTERFACE_CODE} language="java" showLineNumbers={false} />
      </div>

      <Admonition type="security" title={language === 'en' ? 'Untrusted Broker Model' : 'Modèle de Broker Non Fiable'}>
        {language === 'en'
          ? 'The Broker is structurally untrusted. It handles raw byte sequences and performs no signature validations itself. The verifiers independently resolve and verify signatures, preventing a compromised broker from forging states.'
          : 'Le Broker n\'est pas fiable structurellement. Il manipule des séquences d\'octets brutes et n\'effectue aucune validation. Les vérificateurs valident de manière autonome.'}
      </Admonition>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'BrokerEntry Record' : 'Record BrokerEntry'}
        </h2>
        <CodeBlock code={ENTRY_CODE} language="java" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Custom Implementation Example' : 'Exemple d\'implémentation personnalisée'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 mb-3">
          {language === 'en'
            ? 'You can implement the Broker interface to store metadata in custom datastores (Redis, Consul, DynamoDB, etc.):'
            : 'Vous pouvez implémenter l\'interface Broker pour stocker des métadonnées dans d\'autres outils (Redis, Consul, etc.) :'}
        </p>
        <CodeBlock code={IMPLEMENTATION_CODE} language="java" title="Custom InMemoryBroker implementation" />
      </section>
    </div>
  );
}
