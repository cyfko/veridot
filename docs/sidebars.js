/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  docs: [
    'intro',
    {
      type: 'category',
      label: '📖 Learn Veridot',
      collapsed: false,
      items: [
        'learn/the-problem',
        'learn/how-veridot-works',
        'learn/first-integration',
        'learn/going-distributed',
        'learn/capabilities',
        'learn/session-management',
        'learn/production',
        'learn/glossary',
      ],
    },
    {
      type: 'category',
      label: '🔧 Guides',
      items: [
        'guides/core-concepts',
        'guides/trustroot-setup',
        'guides/signing-tokens',
        'guides/verifying-tokens',
        'guides/revoking-sessions',
        'guides/distribution-modes',
        'guides/session-capacity',
        'guides/error-handling',
        'guides/environment-variables',
      ],
    },
    {
      type: 'category',
      label: '🏗️ Architecture',
      items: [
        'architecture/overview',
        'architecture/security-model',
        'architecture/trust-hierarchy',
        'architecture/taas-architecture',
        'architecture/caching-trustroot',
        'architecture/distributed-consistency',
        'architecture/performance',
        'architecture/resilience-and-failure-modes',
        {
          type: 'category',
          label: '📋 ADRs',
          items: [
            'architecture/adr/index',
          ],
        },
      ],
    },
    {
      type: 'category',
      label: '📜 Protocol Specification',
      items: [
        'protocol/index'
      ],
    },
    {
      type: 'category',
      label: '📖 API Reference',
      items: [
        'api/index',
      ],
    },
    {
      type: 'category',
      label: '📦 Modules',
      items: [
        'modules/veridot-core',
        'modules/veridot-kafka',
        'modules/veridot-databases',
        {
          type: 'category',
          label: '🔐 TrustRoots Ecosystem',
          items: [
            'modules/veridot-trustroots/index',
            'modules/veridot-trustroots/api',
            'modules/veridot-trustroots/core',
            'modules/veridot-trustroots/taas-client',
            'modules/veridot-trustroots/taas-server',
            'modules/veridot-trustroots/taas-deployment',
            'modules/veridot-trustroots/spring-autoconfiguration',
          ],
        },
      ],
    },
    'changelog',
    'contributing',
  ],
};

export default sidebars;
