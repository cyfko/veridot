import { useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { useApp } from '../context/AppContext';

interface FAQItem {
  q: string;
  a: string;
}

function FAQAccordion({ item }: { item: FAQItem }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="border border-slate-200 dark:border-slate-700 rounded-xl overflow-hidden">
      <button
        onClick={() => setOpen(o => !o)}
        className="w-full flex items-start gap-3 px-5 py-4 bg-white dark:bg-slate-900 hover:bg-slate-50 dark:hover:bg-slate-800 text-left transition-colors"
      >
        {open ? <ChevronDown size={16} className="flex-shrink-0 mt-0.5 text-violet-600 dark:text-violet-400" /> : <ChevronRight size={16} className="flex-shrink-0 mt-0.5 text-slate-400" />}
        <span className="font-semibold text-slate-900 dark:text-white text-sm">{item.q}</span>
      </button>
      {open && (
        <div className="px-5 pb-4 pt-0 bg-white dark:bg-slate-900 border-t border-slate-100 dark:border-slate-800">
          <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed pl-6">{item.a}</p>
        </div>
      )}
    </div>
  );
}

export function FAQPage() {
  const { language } = useApp();

  const faqs: FAQItem[] = language === 'en' ? [
    { q: 'What is the minimum Java version required?', a: 'veridot-core requires Java 25+. The broker modules (veridot-kafka and veridot-databases) require Java 17+. If you are on Java 17–24, you can use the broker modules but you need Java 25+ for the core API where GenericSignerVerifier lives.' },
    { q: 'How does verification work without a network call?', a: 'When a signing service publishes a KEY_EPOCH entry, it is propagated to all consumers via the broker (Kafka fan-out or SQL polling). Each verifying service maintains a local cache (RocksDB for Kafka). At verify time, the processor reads from this local cache — sub-millisecond, no network round-trip.' },
    { q: 'How long does revocation take to propagate?', a: 'Revocation propagation time depends on the broker and consumer configuration. With Kafka, it is typically bounded by one consumer poll cycle (default: 500ms). With SQL, it depends on the polling interval. The maximum bound is the reconciliation interval (configurable, default 60 minutes for periodic reconciliation). The LIVENESS=REVOKED entry is instantly visible to any processor that observes it.' },
    { q: 'Can I use Veridot without Kafka?', a: 'Yes. Use veridot-databases, which works with any JDBC-compatible database (PostgreSQL, MySQL, etc.). The trade-off is propagation latency: Kafka uses push-based fan-out (sub-second); SQL uses polling-based propagation (configurable interval).' },
    { q: 'What happens if the broker goes down?', a: 'The processor fails closed. During verify(), if the KEY_EPOCH or LIVENESS entry cannot be read from the local cache (and the cache is stale), the verification is rejected. During sign(), if KEY_EPOCH cannot be published, a BrokerTransportException is thrown and no token is issued. Protocol V4 §13.4 mandates this fail-closed behavior.' },
    { q: 'How do I limit the number of concurrent sessions per user?', a: 'Pass maxSessions and an EvictionPolicy to the GenericSignerVerifier constructor. For example: new GenericSignerVerifier(broker, trust, "svc", key, Algorithm.ED25519, 3, EvictionPolicy.FIFO) limits each group to 3 concurrent sessions, evicting the oldest on overflow.' },
    { q: 'What is the difference between groupId and sequenceId?', a: 'groupId is the logical namespace for a set of sessions — typically a user ID, service ID, or API client ID. sequenceId identifies one specific session within that group — typically a device ID or session UUID. Revocation can target one specific sequenceId or all sessions in a groupId.' },
    { q: 'Can I use a custom serializer for my payload?', a: 'Yes. Pass a Function<Object, String> to BasicConfigurer.builder().serializedBy(...). For deserialization, pass a Function<String, T> to verify(). The built-in BasicConfigurer.deserializer(Class<T>) uses Jackson internally.' },
    { q: 'What algorithms are supported?', a: 'Algorithm.RSA_SHA256 (RSA-SHA256, 0x01), Algorithm.ECDSA_SHA256 (ECDSA-SHA256, 0x02), Algorithm.RSA_PSS (RSA-PSS, 0x03), and Algorithm.ED25519 (Ed25519, 0x04). Ed25519 is the recommended default — it is constant-time and immune to timing attacks (NIST SP 800-186).' },
    { q: 'How are ephemeral keys rotated?', a: 'Key rotation is automatic and managed by the internal KeyRotationService. The default interval is 1440 minutes (24 hours), configurable via the VDOT_KEYS_ROTATION_MINUTES environment variable. Each new KEY_EPOCH is published to the broker with a new version number.' },
    { q: 'What is a TrustRoot and why is it required?', a: 'The TrustRoot is the sole source of cryptographic trust. It resolves issuer identifiers to long-term public keys, independently of the broker. Without it, a malicious actor with broker write access could publish forged KEY_EPOCH entries. The TrustRoot must be provisioned out-of-band (never through the broker).' },
    { q: 'Is INDIRECT mode more secure than DIRECT mode?', a: 'INDIRECT mode keeps the JWT off client-facing channels. The payload never transits over the wire to the client — only a compact messageId is returned. This is useful for sensitive payloads (PII, permissions, etc.) that should not be in a client-readable token. Both modes have the same cryptographic security guarantees.' },
    { q: 'How do watermarks survive service restarts?', a: 'GenericSignerVerifier can use a WatermarkStore for persistence. If VDOT_WATERMARK_PERSISTENCE_FILE is set (env var), it uses FileWatermarkStore with HMAC protection. If the broker implements WatermarkStore (e.g., KafkaBroker), it is used automatically. Without a WatermarkStore, watermarks are lost on restart and the processor must reconcile from scratch.' },
    { q: 'Does Veridot support multi-tenancy?', a: 'Yes. Use different groupId namespaces per tenant, or use different scopes (site: for cross-group tenant config). Dynamic configuration (publishConfig) allows per-scope policy management at runtime.' },
    { q: 'Can the broker read my payload?', a: 'In DIRECT mode, the JWT payload is visible to any service that can decode the base64 content of the JWT (standard JWT format). In INDIRECT mode, the payload is stored in the broker but is not encrypted by Veridot — it is protected by transport security (TLS to Kafka, TLS to DB). If you need payload encryption, apply it in your serializer before calling sign().' },
  ] : [
    { q: 'Quelle est la version Java minimale requise ?', a: 'veridot-core nécessite Java 25+. Les modules broker (veridot-kafka et veridot-databases) nécessitent Java 17+. Si vous êtes sur Java 17–24, vous pouvez utiliser les modules broker mais vous avez besoin de Java 25+ pour l\'API centrale où vit GenericSignerVerifier.' },
    { q: 'Comment la vérification fonctionne-t-elle sans appel réseau ?', a: 'Quand un service signataire publie une entrée KEY_EPOCH, elle est propagée à tous les consommateurs via le broker (fan-out Kafka ou polling SQL). Chaque service vérificateur maintient un cache local (RocksDB pour Kafka). Au moment de la vérification, le processeur lit depuis ce cache local — sous-milliseconde, pas d\'aller-retour réseau.' },
    { q: 'Combien de temps prend la propagation de révocation ?', a: 'Le temps de propagation de révocation dépend du broker et de la configuration du consommateur. Avec Kafka, il est typiquement borné par un cycle de poll du consommateur (défaut : 500 ms). Avec SQL, il dépend de l\'intervalle de polling. La borne maximale est l\'intervalle de réconciliation (configurable, défaut 60 minutes pour la réconciliation périodique).' },
    { q: 'Puis-je utiliser Veridot sans Kafka ?', a: 'Oui. Utilisez veridot-databases, qui fonctionne avec n\'importe quelle base de données compatible JDBC (PostgreSQL, MySQL, etc.). Le compromis est la latence de propagation : Kafka utilise le fan-out push (sous la seconde) ; SQL utilise la propagation par polling (intervalle configurable).' },
    { q: 'Que se passe-t-il si le broker tombe ?', a: 'Le processeur échoue fermé. Pendant verify(), si l\'entrée KEY_EPOCH ou LIVENESS ne peut pas être lue depuis le cache local (et que le cache est périmé), la vérification est rejetée. Pendant sign(), si KEY_EPOCH ne peut pas être publié, une BrokerTransportException est lancée et aucun token n\'est émis. Le Protocole V4 §13.4 impose ce comportement fail-closed.' },
    { q: 'Comment limiter le nombre de sessions concurrentes par utilisateur ?', a: 'Passez maxSessions et une EvictionPolicy au constructeur de GenericSignerVerifier. Par exemple : new GenericSignerVerifier(broker, trust, "svc", key, Algorithm.ED25519, 3, EvictionPolicy.FIFO) limite chaque groupe à 3 sessions concurrentes, évincant la plus ancienne en cas de dépassement.' },
    { q: 'Quelle est la différence entre groupId et sequenceId ?', a: 'groupId est le namespace logique pour un ensemble de sessions — typiquement un ID utilisateur, ID service ou ID client API. sequenceId identifie une session spécifique dans ce groupe — typiquement un ID d\'appareil ou UUID de session. La révocation peut cibler un sequenceId spécifique ou toutes les sessions dans un groupId.' },
    { q: 'Quels algorithmes sont supportés ?', a: 'Algorithm.RSA_SHA256 (RSA-SHA256, 0x01), Algorithm.ECDSA_SHA256 (ECDSA-SHA256, 0x02), Algorithm.RSA_PSS (RSA-PSS, 0x03) et Algorithm.ED25519 (Ed25519, 0x04). Ed25519 est le défaut recommandé — il est constant dans le temps et immunisé contre les attaques par timing (NIST SP 800-186).' },
    { q: 'Le broker peut-il lire mon payload ?', a: 'En mode DIRECT, le payload JWT est visible pour tout service pouvant décoder le contenu base64 du JWT (format JWT standard). En mode INDIRECT, le payload est stocké dans le broker mais n\'est pas chiffré par Veridot — il est protégé par la sécurité de transport (TLS vers Kafka, TLS vers DB). Si vous avez besoin de chiffrement du payload, appliquez-le dans votre sérialiseur avant d\'appeler sign().' },
  ];

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">Reference</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">FAQ</h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Frequently asked questions about Veridot.'
            : 'Questions fréquemment posées sur Veridot.'}
        </p>
      </div>
      <div className="space-y-2">
        {faqs.map((faq, i) => (
          <FAQAccordion key={i} item={faq} />
        ))}
      </div>
    </div>
  );
}
