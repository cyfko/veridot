import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const MAVEN_SPRING = `<dependencies>
  <!-- Veridot Core -->
  <dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-core</artifactId>
    <version>4.0.0</version>
  </dependency>

  <!-- Veridot Kafka Broker -->
  <dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-kafka</artifactId>
    <version>4.0.0</version>
  </dependency>

  <!-- Spring Boot Security Starter -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
  </dependency>

  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
  </dependency>
</dependencies>`;

const PROPERTIES_BEAN = `@ConfigurationProperties(prefix = "veridot")
public record VeridotProperties(
    String signerId,
    String privateKeyBase64,
    Map<String, String> trustRootsBase64,
    String kafkaBootstrapServers,
    String localDbPath
) {}`;

const CONFIG_BEAN = `@Configuration
@EnableConfigurationProperties(VeridotProperties.class)
@RequiredArgsConstructor
public class VeridotAutoConfiguration {

    private final VeridotProperties properties;

    // ── 1. Parse ephemerals & root key from Base64 (environment friendly) ──
    @Bean
    public PrivateKey veridotLongTermKey() throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(
            properties.privateKeyBase64().replaceAll("\\s", "")
        );
        return KeyFactory.getInstance("RSA")
            .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }

    // ── 2. Pre-loaded Memory TrustRoot (No disk IO on the request path!) ──
    @Bean
    public TrustRoot veridotTrustRoot() {
        Map<String, PublicKey> preloadedKeys = new ConcurrentHashMap<>();
        
        properties.trustRootsBase64().forEach((signer, pem) -> {
            try {
                byte[] keyBytes = Base64.getDecoder().decode(pem.replaceAll("\\s", ""));
                PublicKey key = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(keyBytes));
                preloadedKeys.put(signer, key);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse public key for " + signer, e);
            }
        });

        return preloadedKeys::get;
    }

    // ── 3. Kafka Broker instantiation ──
    @Bean
    public Broker veridotBroker() {
        Properties p = new Properties();
        p.setProperty("bootstrap.servers", properties.kafkaBootstrapServers());
        p.setProperty("embedded.db.path", properties.localDbPath());
        return KafkaBroker.of(p);
    }

    // ── 4. Main orchestrator with destruction hook ──
    @Bean(destroyMethod = "close")
    public GenericSignerVerifier veridot(
            Broker broker, TrustRoot trust, PrivateKey longTermKey) {
        return new GenericSignerVerifier(
            broker, trust, properties.signerId(), longTermKey,
            Algorithm.ED25519
        );
    }

    @Bean public DataSigner dataSigner(GenericSignerVerifier sv) { return sv; }
    @Bean public TokenVerifier tokenVerifier(GenericSignerVerifier sv) { return sv; }
    @Bean public TokenRevoker tokenRevoker(GenericSignerVerifier sv) { return sv; }
}`;

const SECURITY_TOKEN = `public class VeridotAuthenticationToken extends AbstractAuthenticationToken {
    private final String principal;
    private final String groupId;
    private final String sequenceId;

    public VeridotAuthenticationToken(String principal, String groupId, String sequenceId, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        this.groupId = groupId;
        this.sequenceId = sequenceId;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() { return ""; }

    @Override
    public Object getPrincipal() { return this.principal; }

    public String getGroupId() { return this.groupId; }

    public String getSequenceId() { return this.sequenceId; }
}`;

const FILTER = `@Component
@RequiredArgsConstructor
public class VeridotAuthFilter extends OncePerRequestFilter {

    private final TokenVerifier verifier;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response); // Let Spring Security entry point handle it
            return;
        }

        String token = header.substring(7);
        try {
            // Under 1ms memory lookup
            VerifiedData<String> verified = verifier.verify(token, s -> s);
            
            // Build Spring Security Authentication object
            var auth = new VeridotAuthenticationToken(
                verified.data(),       // Principal (e.g. user email)
                verified.groupId(),    // Group ID
                verified.sequenceId(), // Session ID
                List.of(new SimpleGrantedAuthority("ROLE_USER")) // Map roles as needed
            );
            
            // Bind to Spring Security Context
            SecurityContextHolder.getContext().setAuthentication(auth);
            
            chain.doFilter(request, response);
        } catch (BrokerExtractionException e) {
            SecurityContextHolder.clearContext();
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or revoked token");
        }
    }
}`;

const CONTROLLER = `@RestController
@RequestMapping("/api/orders")
public class OrderController {

    // Inject user principal & custom token parameters in controller methods
    @GetMapping
    @PreAuthorize("hasRole('ROLE_USER')")
    public ResponseEntity<List<Order>> getOrders(
            @AuthenticationPrincipal String email,
            Authentication authentication) {
        
        VeridotAuthenticationToken veridotAuth = (VeridotAuthenticationToken) authentication;
        String tenantId = veridotAuth.getGroupId();
        
        return ResponseEntity.ok(orderService.findOrders(tenantId, email));
    }
}`;

export function SpringBootPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">
          {language === 'en' ? 'Guides · Integrations' : 'Guides · Intégrations'}
        </p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">Spring Security Integration</h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'A professional problem-solution guide to integrating Veridot verification filters into Spring Security architectures.'
            : 'Un guide problème-solution professionnel pour intégrer les filtres de vérification Veridot dans Spring Security.'}
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
              ? 'Trivial filter implementations often bypass Spring Security entirely, forcing controllers to fetch attributes manually. Additionally, performing synchronous file-system key reads inside HTTP requests blocks servlet threads and compromises performance.'
              : 'Les filtres de sécurité naïfs contournent Spring Security, forçant les contrôleurs à lire manuellement les attributs de requête. De plus, effectuer des lectures de fichiers synchrones sur le chemin critique HTTP bloque les threads servlet et dégrade les performances.'}
          </p>
        </div>

        <div className="border border-emerald-200 dark:border-emerald-900 bg-emerald-50/50 dark:bg-emerald-950/10 rounded-xl p-5">
          <h3 className="text-emerald-700 dark:text-emerald-400 font-bold text-base mb-2">
            {language === 'en' ? 'The Solution' : 'La Solution'}
          </h3>
          <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
            {language === 'en'
              ? 'Veridot parameters are bound through Spring Security AuthenticationTokens and validated against a pre-loaded, in-memory TrustRoot. Resources are closed cleanly via Bean destruction hooks.'
              : 'Les paramètres de session Veridot sont exposés via un AuthenticationToken de Spring Security. La confiance est résolue depuis une map de clés pré-chargée en mémoire au démarrage, sans I/O disque pendant l\'appel.'}
          </p>
        </div>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? '1. Maven Dependencies' : '1. Dépendances Maven'}
        </h2>
        <CodeBlock code={MAVEN_SPRING} language="xml" title="pom.xml" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? '2. Properties Binding' : '2. Liaison des Propriétés'}
        </h2>
        <CodeBlock code={PROPERTIES_BEAN} language="java" title="VeridotProperties.java" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? '3. Configuration Bean' : '3. Configuration Automatique'}
        </h2>
        <CodeBlock code={CONFIG_BEAN} language="java" title="VeridotAutoConfiguration.java" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? '4. Spring Security Authentication Token' : '4. Jeton d\'Authentification Spring Security'}
        </h2>
        <CodeBlock code={SECURITY_TOKEN} language="java" title="VeridotAuthenticationToken.java" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? '5. HTTP Security Filter' : '5. Filtre de Sécurité HTTP'}
        </h2>
        <CodeBlock code={FILTER} language="java" title="VeridotAuthFilter.java" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? '6. Secured REST Controller' : '6. Contrôleur REST Sécurisé'}
        </h2>
        <CodeBlock code={CONTROLLER} language="java" title="OrderController.java" />
      </section>
    </div>
  );
}
