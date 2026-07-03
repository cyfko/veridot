/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  docs: [
    'intro',
    {
      type: 'category',
      label: '🚀 Getting Started',
      collapsed: false,
      items: [
        'getting-started/what-is-veridot',
        'getting-started/how-it-works',
        'getting-started/quickstart',
        'getting-started/choosing-a-broker',
        'getting-started/installation',
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
        'architecture/tad-architecture',
        'architecture/caching-trustroot',
        'architecture/distributed-consistency',
        'architecture/protocol-evolution',
        'architecture/performance',
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
        {
          type: 'category',
          label: 'V4 (Current)',
          items: [
            'protocol/v4/index',
            'protocol/v4/wire-format',
            'protocol/v4/entry-types',
            'protocol/v4/key-epoch',
            'protocol/v4/capability',
            'protocol/v4/liveness',
            'protocol/v4/error-codes',
          ],
        },
        {
          type: 'category',
          label: 'V3 (Archived)',
          items: [
            'protocol/v3/index',
          ],
        },
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
            'modules/veridot-trustroots/tad-client',
            'modules/veridot-trustroots/tad-server',
            'modules/veridot-trustroots/tad-deployment',
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
