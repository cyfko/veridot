import { Link, useLocation } from 'react-router-dom';
import { Construction, ArrowLeft } from 'lucide-react';
import { useApp } from '../context/AppContext';

export function PlaceholderPage() {
  const { language } = useApp();
  const location = useLocation();

  return (
    <div className="flex flex-col items-center justify-center min-h-[50vh] text-center space-y-6">
      <div className="h-16 w-16 rounded-2xl bg-amber-100 dark:bg-amber-900/30 flex items-center justify-center">
        <Construction size={32} className="text-amber-600 dark:text-amber-400" />
      </div>
      <div>
        <h1 className="text-2xl font-bold text-slate-900 dark:text-white mb-2">
          {language === 'en' ? 'Page in progress' : 'Page en cours de rédaction'}
        </h1>
        <p className="text-slate-600 dark:text-slate-400 text-sm mb-1">
          {location.pathname}
        </p>
        <p className="text-slate-500 dark:text-slate-500 text-sm">
          {language === 'en'
            ? 'This page is being written. Check back soon or explore another section.'
            : 'Cette page est en cours de rédaction. Revenez bientôt ou explorez une autre section.'}
        </p>
      </div>
      <Link
        to="/"
        className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-violet-700 dark:text-violet-300 bg-violet-50 dark:bg-violet-950/50 hover:bg-violet-100 dark:hover:bg-violet-900/50 rounded-lg transition-colors"
      >
        <ArrowLeft size={14} />
        {language === 'en' ? 'Back to Home' : 'Retour à l\'accueil'}
      </Link>
    </div>
  );
}
