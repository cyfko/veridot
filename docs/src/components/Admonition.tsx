import { AlertTriangle, Info, CheckCircle, XCircle, Lightbulb, Shield } from 'lucide-react';

type AdmonitionType = 'info' | 'warning' | 'danger' | 'tip' | 'success' | 'security';

interface AdmonitionProps {
  type: AdmonitionType;
  title?: string;
  children: React.ReactNode;
}

const configs: Record<AdmonitionType, { icon: typeof Info; bg: string; border: string; title: string; titleColor: string; iconColor: string }> = {
  info: {
    icon: Info,
    bg: 'bg-blue-50 dark:bg-blue-950/30',
    border: 'border-blue-200 dark:border-blue-800',
    title: 'Note',
    titleColor: 'text-blue-700 dark:text-blue-300',
    iconColor: 'text-blue-500',
  },
  warning: {
    icon: AlertTriangle,
    bg: 'bg-amber-50 dark:bg-amber-950/30',
    border: 'border-amber-200 dark:border-amber-800',
    title: 'Warning',
    titleColor: 'text-amber-700 dark:text-amber-300',
    iconColor: 'text-amber-500',
  },
  danger: {
    icon: XCircle,
    bg: 'bg-red-50 dark:bg-red-950/30',
    border: 'border-red-200 dark:border-red-800',
    title: 'Danger',
    titleColor: 'text-red-700 dark:text-red-300',
    iconColor: 'text-red-500',
  },
  tip: {
    icon: Lightbulb,
    bg: 'bg-purple-50 dark:bg-purple-950/30',
    border: 'border-purple-200 dark:border-purple-800',
    title: 'Tip',
    titleColor: 'text-purple-700 dark:text-purple-300',
    iconColor: 'text-purple-500',
  },
  success: {
    icon: CheckCircle,
    bg: 'bg-emerald-50 dark:bg-emerald-950/30',
    border: 'border-emerald-200 dark:border-emerald-800',
    title: 'Success',
    titleColor: 'text-emerald-700 dark:text-emerald-300',
    iconColor: 'text-emerald-500',
  },
  security: {
    icon: Shield,
    bg: 'bg-violet-50 dark:bg-violet-950/30',
    border: 'border-violet-200 dark:border-violet-800',
    title: 'Security',
    titleColor: 'text-violet-700 dark:text-violet-300',
    iconColor: 'text-violet-500',
  },
};

export function Admonition({ type, title, children }: AdmonitionProps) {
  const cfg = configs[type];
  const Icon = cfg.icon;
  return (
    <div className={`flex gap-3 rounded-lg border p-4 my-4 ${cfg.bg} ${cfg.border}`}>
      <Icon size={18} className={`flex-shrink-0 mt-0.5 ${cfg.iconColor}`} />
      <div className="flex-1 min-w-0">
        <p className={`font-semibold text-sm mb-1 ${cfg.titleColor}`}>{title || cfg.title}</p>
        <div className="text-sm text-slate-700 dark:text-slate-300">{children}</div>
      </div>
    </div>
  );
}
