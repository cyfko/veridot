import React, { createContext, useContext, useState, useEffect } from 'react';
import type { Language, Theme } from '../types';

interface AppContextType {
  language: Language;
  setLanguage: (lang: Language) => void;
  theme: Theme;
  setTheme: (theme: Theme) => void;
  toggleTheme: () => void;
  searchQuery: string;
  setSearchQuery: (q: string) => void;
}

const AppContext = createContext<AppContextType | undefined>(undefined);

export function AppProvider({ children }: { children: React.ReactNode }) {
  const [language, setLanguage] = useState<Language>(() => {
    return (localStorage.getItem('veridot-lang') as Language) || 'en';
  });
  const [theme, setTheme] = useState<Theme>(() => {
    return (localStorage.getItem('veridot-theme') as Theme) || 'light';
  });
  const [searchQuery, setSearchQuery] = useState('');

  useEffect(() => {
    localStorage.setItem('veridot-lang', language);
  }, [language]);

  useEffect(() => {
    localStorage.setItem('veridot-theme', theme);
    document.documentElement.classList.toggle('dark', theme === 'dark');
  }, [theme]);

  const toggleTheme = () => setTheme(t => t === 'light' ? 'dark' : 'light');

  return (
    <AppContext.Provider value={{ language, setLanguage, theme, setTheme, toggleTheme, searchQuery, setSearchQuery }}>
      {children}
    </AppContext.Provider>
  );
}

export function useApp() {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error('useApp must be used within AppProvider');
  return ctx;
}
