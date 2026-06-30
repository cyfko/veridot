import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const HIERARCHY_CODE = `// Exception hierarchy
VeridotException (unchecked, extends RuntimeException)
├── BrokerExtractionException   // all verification failures
├── BrokerTransportException    // broker I/O failures during sign()
├── DataSerializationException  // payload serialization failure
├── DataDeserializationException// payload deserialization failure
└── SessionCapacityExceededException // capacity + REJECT policy`;

const VERIFY_CATCH = `try {
    VerifiedData<String> result = sv.verify(token, s -> s);
} catch (BrokerExtractionException e) {
    // All verification failures land here:
    // V4001 INVALID_ENVELOPE
    // V4101 TRUST_RESOLUTION_FAILED
    // V4102 CAPABILITY_NOT_FOUND
    // V4103 CAPABILITY_EXPIRED
    // V4201 STALE_VERSION
    // V4202 LIVENESS_NOT_ESTABLISHED  ← revoked or absent
    // V4203 KEY_EPOCH_EXPIRED         ← token TTL exceeded
    // V4204 SIGALG_KEY_MISMATCH
    // V4401 TRANSPORT_UNAVAILABLE     ← broker unreachable
    response.sendError(401);
} catch (DataDeserializationException e) {
    // Token is cryptographically valid but your deserializer failed.
    log.error("Deserialization error: {}", e.getMessage());
    response.sendError(500);
}`;

const SIGN_CATCH = `try {
    String token = sv.sign(data, config);
} catch (SessionCapacityExceededException e) {
    // maxSessions reached + EvictionPolicy.REJECT
    // e.getGroupId()   → the group that hit the limit
    // e.getMaxSessions() → the configured limit
    log.warn("Session limit for {}: max={}", e.getGroupId(), e.getMaxSessions());
    response.sendError(429, "Too many active sessions");
} catch (DataSerializationException e) {
    // Your serializer threw an exception
    log.error("Could not serialize payload", e);
    response.sendError(500);
} catch (BrokerTransportException e) {
    // Kafka/DB unavailable — KEY_EPOCH was NOT published
    // No token was issued
    log.error("Broker unavailable", e);
    response.sendError(503);
}`;

export function ExceptionsPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">API Reference</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-1">
          {language === 'en' ? 'Exceptions' : 'Exceptions'}
        </h1>
        <p className="text-sm font-mono text-slate-500 dark:text-slate-400 mb-4">io.github.cyfko.veridot.core.exceptions</p>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'All Veridot exceptions extend the unchecked VeridotException. You only need to handle specific subclasses at the application boundary.'
            : 'Toutes les exceptions Veridot étendent l\'exception non vérifiée VeridotException. Vous n\'avez besoin de gérer que des sous-classes spécifiques à la frontière de l\'application.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Exception hierarchy' : 'Hiérarchie des exceptions'}
        </h2>
        <CodeBlock code={HIERARCHY_CODE} language="text" title="Exception hierarchy" showLineNumbers={false} />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Reference' : 'Référence'}
        </h2>
        <div className="space-y-3">
          {[
            {
              name: 'VeridotException',
              pkg: 'io.github.cyfko.veridot.core.exceptions',
              type: 'unchecked',
              desc: language === 'en'
                ? 'Base class for all Veridot exceptions. Extend this if you create custom exceptions. Carries an ErrorCode enum value and a scope string for diagnostic purposes.'
                : 'Classe de base pour toutes les exceptions Veridot. Étendez-la si vous créez des exceptions personnalisées. Porte une valeur enum ErrorCode et une chaîne scope à des fins de diagnostic.',
              thrown: '',
            },
            {
              name: 'BrokerExtractionException',
              pkg: 'io.github.cyfko.veridot.core.exceptions',
              type: 'unchecked',
              desc: language === 'en'
                ? 'Thrown by verify() for any verification failure: invalid token, expired session, revoked session, TrustRoot failure, capability failure, version watermark violation, JWT signature mismatch, broker unavailability. Catch this as your default 401 response.'
                : 'Lancée par verify() pour tout échec de vérification : token invalide, session expirée, session révoquée, échec TrustRoot, échec de capacité, violation du watermark de version, inadéquation de signature JWT, indisponibilité du broker. Attrapez-la comme votre réponse 401 par défaut.',
              thrown: 'verify()',
            },
            {
              name: 'BrokerTransportException',
              pkg: 'io.github.cyfko.veridot.core.exceptions',
              type: 'unchecked',
              desc: language === 'en'
                ? 'Thrown by sign() when the broker is unavailable and the KEY_EPOCH cannot be published. The signing operation fails atomically — no token is issued.'
                : 'Lancée par sign() quand le broker est indisponible et que le KEY_EPOCH ne peut pas être publié. L\'opération de signature échoue atomiquement — aucun token n\'est émis.',
              thrown: 'sign()',
            },
            {
              name: 'DataSerializationException',
              pkg: 'io.github.cyfko.veridot.core.exceptions',
              type: 'unchecked',
              desc: language === 'en'
                ? 'Thrown by sign() when the serializer function throws. The payload could not be converted to a String. No token is issued.'
                : 'Lancée par sign() quand la fonction de sérialisation lance une exception. Le payload n\'a pas pu être converti en String. Aucun token n\'est émis.',
              thrown: 'sign()',
            },
            {
              name: 'DataDeserializationException',
              pkg: 'io.github.cyfko.veridot.core.exceptions',
              type: 'unchecked',
              desc: language === 'en'
                ? 'Thrown by verify() when the token is cryptographically valid but the deserializer function throws. The payload could not be converted to the target type.'
                : 'Lancée par verify() quand le token est cryptographiquement valide mais que la fonction de désérialisation lance une exception. Le payload n\'a pas pu être converti vers le type cible.',
              thrown: 'verify()',
            },
            {
              name: 'SessionCapacityExceededException',
              pkg: 'io.github.cyfko.veridot.core.exceptions',
              type: 'unchecked',
              desc: language === 'en'
                ? 'Thrown by sign() when maxSessions is reached for a group and the policy is EvictionPolicy.REJECT. Carries getGroupId() and getMaxSessions() for diagnostic. No session is created.'
                : 'Lancée par sign() quand maxSessions est atteint pour un groupe et que la politique est EvictionPolicy.REJECT. Porte getGroupId() et getMaxSessions() pour le diagnostic. Aucune session n\'est créée.',
              thrown: 'sign()',
            },
          ].map(ex => (
            <div key={ex.name} className="border border-slate-200 dark:border-slate-700 rounded-xl overflow-hidden">
              <div className="px-4 py-3 bg-slate-50 dark:bg-slate-800/50 border-b border-slate-200 dark:border-slate-700 flex flex-wrap items-center gap-2">
                <code className="font-semibold text-red-700 dark:text-red-300 text-sm">{ex.name}</code>
                <span className="text-xs text-slate-400 font-mono">{ex.pkg}</span>
                {ex.thrown && (
                  <span className="ml-auto text-xs bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-300 px-2 py-0.5 rounded font-mono">
                    {language === 'en' ? 'thrown by ' : 'lancée par '}{ex.thrown}
                  </span>
                )}
              </div>
              <div className="px-4 py-3 bg-white dark:bg-slate-900">
                <p className="text-sm text-slate-600 dark:text-slate-400">{ex.desc}</p>
              </div>
            </div>
          ))}
        </div>
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Error handling patterns' : 'Patterns de gestion des erreurs'}
        </h2>

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2">
          {language === 'en' ? 'During verify()' : 'Pendant verify()'}
        </h3>
        <CodeBlock code={VERIFY_CATCH} language="java" title="Error handling — verify()" />

        <h3 className="font-semibold text-slate-800 dark:text-slate-200 mb-2 mt-6">
          {language === 'en' ? 'During sign()' : 'Pendant sign()'}
        </h3>
        <CodeBlock code={SIGN_CATCH} language="java" title="Error handling — sign()" />
      </section>

      <Admonition type="tip" title={language === 'en' ? 'Best practice: don\'t catch VeridotException directly' : 'Bonne pratique : ne pas attraper VeridotException directement'}>
        {language === 'en'
          ? 'Catch specific subclasses (BrokerExtractionException, SessionCapacityExceededException, etc.) to give each failure class its correct HTTP status and log message. Catching the base VeridotException hides important distinctions between revocation, transport errors, and deserialization failures.'
          : 'Attrapez des sous-classes spécifiques (BrokerExtractionException, SessionCapacityExceededException, etc.) pour donner à chaque classe d\'échec son bon statut HTTP et message de log. Attraper la base VeridotException cache des distinctions importantes entre révocation, erreurs de transport et échecs de désérialisation.'}
      </Admonition>
    </div>
  );
}
