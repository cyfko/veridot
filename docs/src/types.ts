export type Language = 'en' | 'fr';
export type Theme = 'light' | 'dark';

export interface NavItem {
  label: { en: string; fr: string };
  path?: string;
  children?: NavItem[];
  icon?: string;
}
