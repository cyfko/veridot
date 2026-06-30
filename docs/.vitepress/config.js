import { defineConfig } from 'vitepress'

export default defineConfig({
  title: "Veridot",
  description: "Official Documentation of Veridot — Distributed Token Verification Framework",
  head: [
    ['link', { rel: 'icon', href: '/favicon.ico' }]
  ],
  locales: {
    root: {
      label: 'English',
      lang: 'en',
      themeConfig: {
        nav: [
          { text: 'Guide', link: '/guide/getting-started' },
          { text: 'Concepts', link: '/concepts/protocol' },
          { text: 'API Reference', link: '/api/reference' },
          { text: 'DevSecOps', link: '/devsecops/architectures' }
        ],
        sidebar: [
          {
            text: 'Getting Started',
            items: [
              { text: 'Why Veridot?', link: '/guide/why' },
              { text: 'Installation', link: '/guide/installation' },
              { text: 'Getting Started', link: '/guide/getting-started' }
            ]
          },
          {
            text: 'Core Concepts',
            items: [
              { text: 'Protocol V4 Specification', link: '/concepts/protocol' },
              { text: 'Cryptographic Model', link: '/concepts/cryptography' },
              { text: 'State Consistency & Monotonicity', link: '/concepts/consistency' }
            ]
          },
          {
            text: 'Java API Reference',
            items: [
              { text: 'Overview', link: '/api/reference' },
              { text: 'DataSigner', link: '/api/signer' },
              { text: 'TokenVerifier', link: '/api/verifier' },
              { text: 'TokenTracker & TokenRevoker', link: '/api/tracker-revoker' }
            ]
          },
          {
            text: 'DevSecOps & Deployment',
            items: [
              { text: 'Architecture Blueprints', link: '/devsecops/architectures' },
              { text: 'Key & Trust Management', link: '/devsecops/keys' }
            ]
          },
          {
            text: 'Resources',
            items: [
              { text: 'FAQ & Troubleshooting', link: '/resources/faq' },
              { text: 'Contributing', link: '/resources/contributing' }
            ]
          }
        ]
      }
    },
    fr: {
      label: 'Français',
      lang: 'fr',
      link: '/fr/',
      themeConfig: {
        nav: [
          { text: 'Guide', link: '/fr/guide/getting-started' },
          { text: 'Concepts', link: '/fr/concepts/protocol' },
          { text: 'API Référence', link: '/fr/api/reference' },
          { text: 'DevSecOps', link: '/fr/devsecops/architectures' }
        ],
        sidebar: [
          {
            text: 'Mise en Route',
            items: [
              { text: 'Pourquoi Veridot ?', link: '/fr/guide/why' },
              { text: 'Installation', link: '/fr/guide/installation' },
              { text: 'Démarrage Rapide', link: '/fr/guide/getting-started' }
            ]
          },
          {
            text: 'Concepts Clés',
            items: [
              { text: 'Spécification du Protocole V4', link: '/fr/concepts/protocol' },
              { text: 'Modèle Cryptographique', link: '/fr/concepts/cryptography' },
              { text: 'Cohérence et Monotonie', link: '/fr/concepts/consistency' }
            ]
          },
          {
            text: 'Référence API Java',
            items: [
              { text: 'Présentation', link: '/fr/api/reference' },
              { text: 'DataSigner', link: '/fr/api/signer' },
              { text: 'TokenVerifier', link: '/fr/api/verifier' },
              { text: 'TokenTracker & TokenRevoker', link: '/fr/api/tracker-revoker' }
            ]
          },
          {
            text: 'DevSecOps & Déploiement',
            items: [
              { text: 'Modèles d\'Architecture', link: '/fr/devsecops/architectures' },
              { text: 'Gestion des Clés & Confiance', link: '/fr/devsecops/keys' }
            ]
          },
          {
            text: 'Ressources',
            items: [
              { text: 'FAQ & Dépannage', link: '/fr/resources/faq' },
              { text: 'Contribution', link: '/fr/resources/contributing' }
            ]
          }
        ]
      }
    }
  },
  themeConfig: {
    logo: '/logo.svg',
    search: {
      provider: 'local'
    },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/cyfko/veridot' }
    ],
    footer: {
      message: 'Released under the MIT License.',
      copyright: 'Copyright © 2026 Frank Cyrille KOSSI KOSSI'
    }
  }
})
