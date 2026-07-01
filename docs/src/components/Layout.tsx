import { useState, useEffect } from 'react';
import { Link, useLocation } from 'react-router-dom';
import {
  Moon, Sun, Search, Menu, X, Globe, ChevronRight, ChevronDown,
  Home, BookOpen, Code2, Shield, Settings, GitBranch, Layers, BarChart3, Package
} from 'lucide-react';
import { useApp } from '../context/AppContext';

const NAV_ITEMS = [
  {
    label: { en: 'Getting Started', fr: 'Démarrage' },
    icon: Home,
    children: [
      { label: { en: 'Introduction', fr: 'Introduction' }, path: '/' },
      { label: { en: 'Why Veridot?', fr: 'Pourquoi Veridot ?' }, path: '/why-veridot' },
      { label: { en: 'Use Cases & Patterns', fr: 'Cas d\'Usage & Patterns' }, path: '/use-cases' },
      { label: { en: 'Installation', fr: 'Installation' }, path: '/installation' },
      { label: { en: 'Quick Start', fr: 'Démarrage rapide' }, path: '/quickstart' },
      { label: { en: 'Core Concepts', fr: 'Concepts fondamentaux' }, path: '/concepts' },
    ]
  },
  {
    label: { en: 'Guides', fr: 'Guides' },
    icon: BookOpen,
    children: [
      { label: { en: 'Sign & Verify', fr: 'Signer & Vérifier' }, path: '/guides/sign-verify' },
      { label: { en: 'Revoking Tokens', fr: 'Révoquer des tokens' }, path: '/guides/revocation' },
      { label: { en: 'Session Capacity', fr: 'Capacité de session' }, path: '/guides/session-capacity' },
      { label: { en: 'Distribution Modes', fr: 'Modes de distribution' }, path: '/guides/distribution-modes' },
      { label: { en: 'Custom Serialization', fr: 'Sérialisation custom' }, path: '/guides/serialization' },
      { label: { en: 'Spring Boot', fr: 'Spring Boot' }, path: '/guides/spring-boot' },
      { label: { en: 'Dynamic Config', fr: 'Config dynamique' }, path: '/guides/dynamic-config' },
    ]
  },
  {
    label: { en: 'Protocol V4', fr: 'Protocole V4' },
    icon: Layers,
    children: [
      { label: { en: 'Overview', fr: 'Vue d\'ensemble' }, path: '/protocol/overview' },
      { label: { en: 'Wire Format', fr: 'Format binaire' }, path: '/protocol/wire-format' },
      { label: { en: 'Entry Types', fr: 'Types d\'entrée' }, path: '/protocol/entry-types' },
      { label: { en: 'Verification Flow', fr: 'Flux de vérification' }, path: '/protocol/verification-flow' },
      { label: { en: 'Liveness & Revocation', fr: 'Liveness & Révocation' }, path: '/protocol/liveness' },
      { label: { en: 'State Consistency', fr: 'Cohérence d\'état' }, path: '/protocol/consistency' },
    ]
  },
  {
    label: { en: 'API Reference', fr: 'Référence API' },
    icon: Code2,
    children: [
      { label: { en: 'DataSigner', fr: 'DataSigner' }, path: '/api/data-signer' },
      { label: { en: 'TokenVerifier', fr: 'TokenVerifier' }, path: '/api/token-verifier' },
      { label: { en: 'TokenRevoker', fr: 'TokenRevoker' }, path: '/api/token-revoker' },
      { label: { en: 'TokenTracker', fr: 'TokenTracker' }, path: '/api/token-tracker' },
      { label: { en: 'GenericSignerVerifier', fr: 'GenericSignerVerifier' }, path: '/api/generic-signer-verifier' },
      { label: { en: 'TrustRoot', fr: 'TrustRoot' }, path: '/api/trust-root' },
      { label: { en: 'Broker', fr: 'Broker' }, path: '/api/broker' },
      { label: { en: 'VerifiedData', fr: 'VerifiedData' }, path: '/api/verified-data' },
      { label: { en: 'Exceptions', fr: 'Exceptions' }, path: '/api/exceptions' },
      { label: { en: 'Enums & Records', fr: 'Enums & Records' }, path: '/api/enums' },
    ]
  },
  {
    label: { en: 'Brokers', fr: 'Brokers' },
    icon: Package,
    children: [
      { label: { en: 'Choosing a Broker', fr: 'Choisir un broker' }, path: '/brokers/overview' },
      { label: { en: 'veridot-kafka', fr: 'veridot-kafka' }, path: '/brokers/kafka' },
      { label: { en: 'veridot-databases', fr: 'veridot-databases' }, path: '/brokers/databases' },
    ]
  },
  {
    label: { en: 'Security', fr: 'Sécurité' },
    icon: Shield,
    children: [
      { label: { en: 'Security Model', fr: 'Modèle de sécurité' }, path: '/security/model' },
      { label: { en: 'Cryptography', fr: 'Cryptographie' }, path: '/security/cryptography' },
      { label: { en: 'TrustRoot in Production', fr: 'TrustRoot en production' }, path: '/security/trust-root' },
      { label: { en: 'Threat Model', fr: 'Modèle de menaces' }, path: '/security/threats' },
      { label: { en: 'Error Codes', fr: 'Codes d\'erreur' }, path: '/security/error-codes' },
    ]
  },
  {
    label: { en: 'Observability', fr: 'Observabilité' },
    icon: BarChart3,
    children: [
      { label: { en: 'Metrics', fr: 'Métriques' }, path: '/observability/metrics' },
      { label: { en: 'Logging', fr: 'Journalisation' }, path: '/observability/logging' },
    ]
  },
  {
    label: { en: 'DevSecOps', fr: 'DevSecOps' },
    icon: Settings,
    children: [
      { label: { en: 'Deployment Models', fr: 'Modèles de déploiement' }, path: '/devsecops/deployment' },
      { label: { en: 'Docker & Kubernetes', fr: 'Docker & Kubernetes' }, path: '/devsecops/kubernetes' },
      { label: { en: 'Key Management', fr: 'Gestion des clés' }, path: '/devsecops/key-management' },
    ]
  },
  {
    label: { en: 'Reference', fr: 'Référence' },
    icon: GitBranch,
    children: [
      { label: { en: 'Glossary', fr: 'Glossaire' }, path: '/glossary' },
      { label: { en: 'FAQ', fr: 'FAQ' }, path: '/faq' },
      { label: { en: 'Troubleshooting', fr: 'Dépannage' }, path: '/troubleshooting' },
      { label: { en: 'Changelog', fr: 'Changelog' }, path: '/changelog' },
    ]
  },
];

function NavSection({ item }: { item: typeof NAV_ITEMS[0] }) {
  const location = useLocation();
  const { language } = useApp();
  const [open, setOpen] = useState(() => {
    if (item.children) {
      return item.children.some(c => c.path === location.pathname);
    }
    return false;
  });

  const Icon = item.icon;
  const isActive = item.children?.some(c => c.path === location.pathname);

  return (
    <div className="mb-1">
      <button
        onClick={() => setOpen(o => !o)}
        className={`w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-semibold transition-colors
          ${isActive
            ? 'text-violet-700 dark:text-violet-300 bg-violet-50 dark:bg-violet-950/40'
            : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-100 hover:bg-slate-100 dark:hover:bg-slate-800'
          }`}
      >
        {Icon && <Icon size={15} className="flex-shrink-0" />}
        <span className="flex-1 text-left">{item.label[language]}</span>
        {open ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
      </button>
      {open && item.children && (
        <div className="ml-4 mt-1 space-y-0.5 border-l border-slate-200 dark:border-slate-700 pl-3">
          {item.children.map(child => (
            <NavChild key={child.path} item={child} />
          ))}
        </div>
      )}
    </div>
  );
}

function NavChild({ item }: { item: { label: { en: string; fr: string }; path: string } }) {
  const location = useLocation();
  const { language } = useApp();
  const isActive = location.pathname === item.path;

  return (
    <Link
      to={item.path}
      className={`block px-3 py-1.5 rounded-md text-sm transition-colors
        ${isActive
          ? 'text-violet-700 dark:text-violet-300 bg-violet-50 dark:bg-violet-950/40 font-medium'
          : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800'
        }`}
    >
      {item.label[language]}
    </Link>
  );
}

export function Layout({ children }: { children: React.ReactNode }) {
  const location = useLocation();
  const { theme, toggleTheme, language, setLanguage, searchQuery, setSearchQuery } = useApp();
  const [sidebarOpen, setSidebarOpen] = useState(false);

  useEffect(() => {
    // Dynamic page title & description for SEO optimization
    let label = '';
    for (const section of NAV_ITEMS) {
      if (section.children) {
        const match = section.children.find(child => child.path === location.pathname);
        if (match) {
          label = match.label[language];
          break;
        }
      }
    }

    if (location.pathname === '/') {
      label = language === 'en' ? 'Distributed Token Verification Protocol' : 'Protocole de Vérification Distribuée de Jeton';
    }

    const titleText = label ? `Veridot — ${label}` : 'Veridot — Distributed Token Verification Protocol';
    document.title = titleText;

    const metaDesc = document.querySelector("meta[name='description']");
    if (metaDesc) {
      const descText = language === 'en'
        ? `Official documentation for Veridot v4.0 - ${label || 'Distributed Token Verification Protocol'}. A Java library for distributed token signature and validation.`
        : `Documentation officielle de Veridot v4.0 - ${label || 'Protocole de Vérification Distribuée'}. Bibliothèque Java de signature et validation distribuée de jetons.`;
      metaDesc.setAttribute('content', descText);
    }
  }, [location.pathname, language]);

  return (
    <div className="min-h-screen bg-white dark:bg-slate-950 text-slate-900 dark:text-slate-100">
      {/* Top Header */}
      <header className="fixed top-0 left-0 right-0 z-50 h-14 border-b border-slate-200 dark:border-slate-800 bg-white/90 dark:bg-slate-950/90 backdrop-blur-md">
        <div className="flex items-center h-full px-4 gap-4">
          <button
            onClick={() => setSidebarOpen(o => !o)}
            className="lg:hidden p-1.5 rounded-md text-slate-500 hover:text-slate-900 dark:hover:text-slate-100"
          >
            {sidebarOpen ? <X size={20} /> : <Menu size={20} />}
          </button>

          <Link to="/" className="flex items-center gap-2.5 flex-shrink-0">
            <div className="h-8 w-8 rounded-lg bg-gradient-to-br from-violet-600 to-indigo-600 flex items-center justify-center shadow">
              <Shield size={16} className="text-white" />
            </div>
            <span className="font-bold text-slate-900 dark:text-white text-lg tracking-tight">Veridot</span>
            <span className="hidden sm:inline-block text-xs font-medium px-1.5 py-0.5 rounded bg-violet-100 dark:bg-violet-900/50 text-violet-700 dark:text-violet-300">
              v4.0
            </span>
          </Link>

          <div className="flex-1 max-w-xl mx-4 hidden sm:block">
            <div className="relative">
              <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
              <input
                type="text"
                placeholder={language === 'en' ? 'Search documentation...' : 'Rechercher dans la documentation...'}
                value={searchQuery}
                onChange={e => setSearchQuery(e.target.value)}
                className="w-full pl-9 pr-4 py-1.5 text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 text-slate-900 dark:text-slate-100 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-violet-500 focus:border-transparent"
              />
            </div>
          </div>

          <div className="ml-auto flex items-center gap-2">
            <a
              href="https://github.com/cyfko/veridot"
              target="_blank"
              rel="noopener noreferrer"
              className="hidden md:flex items-center gap-1.5 px-3 py-1.5 text-sm text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-100 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
            >
              <svg viewBox="0 0 24 24" fill="currentColor" className="h-4 w-4">
                <path d="M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.531 1.032 1.531 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0112 6.844c.85.004 1.705.115 2.504.337 1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.019 10.019 0 0022 12.017C22 6.484 17.522 2 12 2z" />
              </svg>
              GitHub
            </a>

            <button
              onClick={() => setLanguage(language === 'en' ? 'fr' : 'en')}
              className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-100 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors font-medium"
            >
              <Globe size={15} />
              {language === 'en' ? 'FR' : 'EN'}
            </button>

            <button
              onClick={toggleTheme}
              className="p-1.5 rounded-lg text-slate-500 hover:text-slate-900 dark:hover:text-slate-100 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
            >
              {theme === 'light' ? <Moon size={17} /> : <Sun size={17} />}
            </button>
          </div>
        </div>
      </header>

      <div className="flex pt-14">
        {sidebarOpen && (
          <div
            className="fixed inset-0 z-30 bg-black/40 lg:hidden"
            onClick={() => setSidebarOpen(false)}
          />
        )}

        <aside
          className={`fixed top-14 left-0 bottom-0 z-40 w-64 border-r border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-950 overflow-y-auto transition-transform duration-200
            ${sidebarOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0'}`}
        >
          <nav className="p-4 space-y-1">
            {NAV_ITEMS.map((item, i) => (
              <NavSection key={i} item={item} />
            ))}
          </nav>
          <div className="p-4 border-t border-slate-200 dark:border-slate-800 text-xs text-slate-400 dark:text-slate-500">
            <p>Protocol v4.0 · MIT License</p>
            <p>© 2026 Kunrin SA</p>
          </div>
        </aside>

        <main className="flex-1 min-w-0 lg:ml-64">
          <div className="max-w-4xl mx-auto px-4 sm:px-8 py-10">
            {children}
          </div>
        </main>
      </div>
    </div>
  );
}
