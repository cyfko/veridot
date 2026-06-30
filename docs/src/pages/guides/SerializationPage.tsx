import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const CUSTOM_SERIALIZE = `// Custom serializer using a custom Jackson ObjectMapper configuration
ObjectMapper customMapper = new ObjectMapper()
    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

BasicConfigurer config = BasicConfigurer.builder()
    .groupId("analytics-group")
    .validity(3600)
    .serializedBy(obj -> {
        try {
            return customMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new DataSerializationException("JSON conversion failed", e);
        }
    })
    .build();`;

const CUSTOM_DESERIALIZE = `// Custom deserializer function passed to TokenVerifier#verify
Function<String, UserClaims> customDeserializer = json -> {
    try {
        return customMapper.readValue(json, UserClaims.class);
    } catch (JsonProcessingException e) {
        throw new DataDeserializationException("JSON parsing failed", e);
    }
};

VerifiedData<UserClaims> verified = verifier.verify(token, customDeserializer);`;

export function SerializationPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">
          {language === 'en' ? 'Guides · Customization' : 'Guides · Personnalisation'}
        </p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">
          {language === 'en' ? 'Custom Serialization' : 'Sérialisation Personnalisée'}
        </h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'A problem-solution guide to configuring custom serializers for token payloads.'
            : 'Un guide problème-solution pour configurer des sérialiseurs de charge utile personnalisés.'}
        </p>
      </div>

      {/* Problem-Solution section */}
      <div className="grid sm:grid-cols-2 gap-4 my-6">
        <div className="border border-red-200 dark:border-red-900 bg-red-50/50 dark:bg-red-950/10 rounded-xl p-5">
          <h3 className="text-red-700 dark:text-red-400 font-bold text-base mb-2">
            {language === 'en' ? 'The Problem' : 'Le Problème'}
          </h3>
          <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
            {language === 'en'
              ? 'Default JSON serialization configurations might leak fields, fail on custom Java types (like Instant or LocalDate), or introduce Remote Code Execution (RCE) vulnerabilities if default polymorphic typing is enabled.'
              : 'La sérialisation JSON par défaut peut omettre des champs, échouer sur certains types Java (comme Instant ou LocalDate), ou exposer le nœud à des failles RCE si le typage polymorphique est activé.'}
          </p>
        </div>

        <div className="border border-emerald-200 dark:border-emerald-900 bg-emerald-50/50 dark:bg-emerald-950/10 rounded-xl p-5">
          <h3 className="text-emerald-700 dark:text-emerald-400 font-bold text-base mb-2">
            {language === 'en' ? 'The Solution' : 'La Solution'}
          </h3>
          <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
            {language === 'en'
              ? 'Veridot allows passing custom serialization and deserialization functions. Developers can inject customized, secured Jackson ObjectMappers, Protocol Buffers, or gzip compression lambdas directly.'
              : 'Veridot permet d\'injecter vos propres fonctions de sérialisation et désérialisation. Vous pouvez ainsi utiliser un ObjectMapper configuré de façon sûre, Protobuf ou une compression gzip.'}
          </p>
        </div>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? '1. Custom Serialization (Signer)' : '1. Sérialisation personnalisée (Signer)'}
        </h2>
        <CodeBlock code={CUSTOM_SERIALIZE} language="java" title="Configuring custom serialization" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? '2. Custom Deserialization (Verifier)' : '2. Désérialisation personnalisée (Verifier)'}
        </h2>
        <CodeBlock code={CUSTOM_DESERIALIZE} language="java" title="Using custom deserialization" />
      </section>
    </div>
  );
}
