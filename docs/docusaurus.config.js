// @ts-check
import {themes as prismThemes} from 'prism-react-renderer';

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'Veridot',
  tagline: 'Distributed Token Verification Protocol — Sub-ms verification, instant revocation, zero shared secrets.',
  favicon: 'img/favicon.ico',

  future: {
    v4: true,
  },

  url: 'https://cyfko.github.io',
  baseUrl: '/veridot/',

  organizationName: 'cyfko',
  projectName: 'veridot',
  trailingSlash: false,

  onBrokenLinks: 'warn',

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  markdown: {
    mermaid: true,
    hooks: {
      onBrokenMarkdownLinks: 'warn',
    },
  },

  themes: ['@docusaurus/theme-mermaid'],
  plugins: [require.resolve('docusaurus-lunr-search')],

  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          sidebarPath: './sidebars.js',
          editUrl: 'https://github.com/cyfko/veridot/tree/main/docs/',
          showLastUpdateTime: true,
          showLastUpdateAuthor: true,
        },
        blog: false, // Disabled for now
        theme: {
          customCss: './src/css/custom.css',
        },
      }),
    ],
  ],

  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      image: 'img/veridot-social-card.png',
      colorMode: {
        defaultMode: 'dark',
        respectPrefersColorScheme: true,
      },
      metadata: [
        {name: 'keywords', content: 'veridot, distributed token verification, JWT, microservices, java, kafka, revocation, zero trust'},
        {name: 'twitter:card', content: 'summary_large_image'},
      ],
      navbar: {
        title: 'Veridot',
        logo: {
          alt: 'Veridot Logo',
          src: 'img/logo.png',
        },
        items: [
          {
            type: 'docSidebar',
            sidebarId: 'docs',
            position: 'left',
            label: 'Docs',
          },
          {
            to: '/docs/protocol/v4/',
            label: 'Protocol V4',
            position: 'left',
          },
          {
            to: '/docs/api/',
            label: 'API Reference',
            position: 'left',
          },
          {
            href: 'https://central.sonatype.com/search?q=io.github.cyfko.veridot',
            label: 'Maven Central',
            position: 'right',
          },
          {
            href: 'https://github.com/cyfko/veridot',
            label: 'GitHub',
            position: 'right',
          },
        ],
      },
      footer: {
        style: 'dark',
        links: [
          {
            title: 'Learn',
            items: [
              {label: 'Getting Started', to: '/docs/getting-started/what-is-veridot'},
              {label: 'Quickstart', to: '/docs/getting-started/quickstart'},
              {label: 'Guides', to: '/docs/guides/core-concepts'},
            ],
          },
          {
            title: 'Reference',
            items: [
              {label: 'Protocol V4', to: '/docs/protocol/v4/'},
              {label: 'API Reference', to: '/docs/api/'},
              {label: 'Changelog', to: '/docs/changelog'},
            ],
          },
          {
            title: 'More',
            items: [
              {label: 'GitHub', href: 'https://github.com/cyfko/veridot'},
              {label: 'Maven Central', href: 'https://central.sonatype.com/search?q=io.github.cyfko.veridot'},
              {label: 'Contributing', to: '/docs/contributing'},
            ],
          },
        ],
        copyright: `Copyright © ${new Date().getFullYear()} Kunrin SA. Built with Docusaurus. Licensed under MIT.`,
      },
      prism: {
        theme: prismThemes.github,
        darkTheme: prismThemes.dracula,
        additionalLanguages: ['java', 'bash', 'json', 'yaml', 'markup'],
      },
      mermaid: {
        theme: {light: 'default', dark: 'dark'},
        options: {
          flowchart: {
            curve: 'linear',
          },
        },
      },
      tableOfContents: {
        minHeadingLevel: 2,
        maxHeadingLevel: 4,
      },
    }),
};

export default config;
