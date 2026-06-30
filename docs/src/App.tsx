import { HashRouter, Routes, Route } from 'react-router-dom';
import { AppProvider } from './context/AppContext';
import { Layout } from './components/Layout';

// Pages
import { HomePage } from './pages/HomePage';
import { WhyVeridotPage } from './pages/WhyVeridotPage';
import { UseCasesPage } from './pages/UseCasesPage';
import { InstallationPage } from './pages/InstallationPage';
import { QuickStartPage } from './pages/QuickStartPage';
import { ConceptsPage } from './pages/ConceptsPage';
import { GlossaryPage } from './pages/GlossaryPage';
import { FAQPage } from './pages/FAQPage';

// API Reference
import { DataSignerPage } from './pages/api/DataSignerPage';
import { TokenVerifierPage } from './pages/api/TokenVerifierPage';
import { TokenRevokerPage } from './pages/api/TokenRevokerPage';
import { TokenTrackerPage } from './pages/api/TokenTrackerPage';
import { GenericSignerVerifierPage } from './pages/api/GenericSignerVerifierPage';
import { TrustRootPage } from './pages/api/TrustRootPage';
import { BrokerPage } from './pages/api/BrokerPage';
import { VerifiedDataPage } from './pages/api/VerifiedDataPage';
import { EnumsPage } from './pages/api/EnumsPage';
import { ExceptionsPage } from './pages/api/ExceptionsPage';

// Protocol
import { ProtocolOverviewPage } from './pages/protocol/ProtocolOverviewPage';
import { WireFormatPage } from './pages/protocol/WireFormatPage';
import { EntryTypesPage } from './pages/protocol/EntryTypesPage';
import { VerificationFlowPage } from './pages/protocol/VerificationFlowPage';
import { LivenessPage } from './pages/protocol/LivenessPage';
import { ConsistencyPage } from './pages/protocol/ConsistencyPage';

// Security
import { SecurityModelPage } from './pages/SecurityPage';
import { CryptographyPage } from './pages/security/CryptographyPage';
import { TrustRootProductionPage } from './pages/security/TrustRootProductionPage';
import { ThreatModelPage } from './pages/security/ThreatModelPage';
import { ErrorCodesPage } from './pages/security/ErrorCodesPage';

// Guides
import { SignVerifyPage } from './pages/guides/SignVerifyPage';
import { RevocationPage } from './pages/guides/RevocationPage';
import { SessionCapacityPage } from './pages/guides/SessionCapacityPage';
import { DistributionModesPage } from './pages/guides/DistributionModesPage';
import { SerializationPage } from './pages/guides/SerializationPage';
import { SpringBootPage } from './pages/guides/SpringBootPage';
import { DynamicConfigPage } from './pages/guides/DynamicConfigPage';

// Brokers
import { BrokersOverviewPage } from './pages/brokers/BrokersOverviewPage';
import { KafkaBrokerPage } from './pages/brokers/KafkaBrokerPage';
import { DatabaseBrokerPage } from './pages/brokers/DatabaseBrokerPage';

// Observability
import { MetricsPage } from './pages/observability/MetricsPage';
import { LoggingPage } from './pages/observability/LoggingPage';

// DevSecOps
import { DeploymentPage } from './pages/devsecops/DeploymentPage';
import { KubernetesPage } from './pages/devsecops/KubernetesPage';
import { KeyManagementPage } from './pages/devsecops/KeyManagementPage';

// Reference
import { TroubleshootingPage } from './pages/troubleshooting/TroubleshootingPage';
import { ChangelogPage } from './pages/changelog/ChangelogPage';

// Placeholder
import { PlaceholderPage } from './pages/PlaceholderPage';

export default function App() {
  return (
    <HashRouter>
      <AppProvider>
        <Layout>
          <Routes>
            {/* Getting Started */}
            <Route path="/" element={<HomePage />} />
            <Route path="/why-veridot" element={<WhyVeridotPage />} />
            <Route path="/use-cases" element={<UseCasesPage />} />
            <Route path="/installation" element={<InstallationPage />} />
            <Route path="/quickstart" element={<QuickStartPage />} />
            <Route path="/concepts" element={<ConceptsPage />} />

            {/* Guides */}
            <Route path="/guides/sign-verify" element={<SignVerifyPage />} />
            <Route path="/guides/revocation" element={<RevocationPage />} />
            <Route path="/guides/session-capacity" element={<SessionCapacityPage />} />
            <Route path="/guides/distribution-modes" element={<DistributionModesPage />} />
            <Route path="/guides/serialization" element={<SerializationPage />} />
            <Route path="/guides/spring-boot" element={<SpringBootPage />} />
            <Route path="/guides/dynamic-config" element={<DynamicConfigPage />} />

            {/* Protocol V4 */}
            <Route path="/protocol/overview" element={<ProtocolOverviewPage />} />
            <Route path="/protocol/wire-format" element={<WireFormatPage />} />
            <Route path="/protocol/entry-types" element={<EntryTypesPage />} />
            <Route path="/protocol/verification-flow" element={<VerificationFlowPage />} />
            <Route path="/protocol/liveness" element={<LivenessPage />} />
            <Route path="/protocol/consistency" element={<ConsistencyPage />} />

            {/* API Reference */}
            <Route path="/api/data-signer" element={<DataSignerPage />} />
            <Route path="/api/token-verifier" element={<TokenVerifierPage />} />
            <Route path="/api/token-revoker" element={<TokenRevokerPage />} />
            <Route path="/api/token-tracker" element={<TokenTrackerPage />} />
            <Route path="/api/generic-signer-verifier" element={<GenericSignerVerifierPage />} />
            <Route path="/api/trust-root" element={<TrustRootPage />} />
            <Route path="/api/broker" element={<BrokerPage />} />
            <Route path="/api/verified-data" element={<VerifiedDataPage />} />
            <Route path="/api/exceptions" element={<ExceptionsPage />} />
            <Route path="/api/enums" element={<EnumsPage />} />

            {/* Brokers */}
            <Route path="/brokers/overview" element={<BrokersOverviewPage />} />
            <Route path="/brokers/kafka" element={<KafkaBrokerPage />} />
            <Route path="/brokers/databases" element={<DatabaseBrokerPage />} />

            {/* Security */}
            <Route path="/security/model" element={<SecurityModelPage />} />
            <Route path="/security/cryptography" element={<CryptographyPage />} />
            <Route path="/security/trust-root" element={<TrustRootProductionPage />} />
            <Route path="/security/threats" element={<ThreatModelPage />} />
            <Route path="/security/error-codes" element={<ErrorCodesPage />} />

            {/* Observability */}
            <Route path="/observability/metrics" element={<MetricsPage />} />
            <Route path="/observability/logging" element={<LoggingPage />} />

            {/* DevSecOps */}
            <Route path="/devsecops/deployment" element={<DeploymentPage />} />
            <Route path="/devsecops/kubernetes" element={<KubernetesPage />} />
            <Route path="/devsecops/key-management" element={<KeyManagementPage />} />

            {/* Reference */}
            <Route path="/glossary" element={<GlossaryPage />} />
            <Route path="/faq" element={<FAQPage />} />
            <Route path="/troubleshooting" element={<TroubleshootingPage />} />
            <Route path="/changelog" element={<ChangelogPage />} />

            {/* 404 */}
            <Route path="*" element={<PlaceholderPage />} />
          </Routes>
        </Layout>
      </AppProvider>
    </HashRouter>
  );
}
