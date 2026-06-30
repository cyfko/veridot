import { useState } from 'react';
import { Check, Copy } from 'lucide-react';

interface CodeBlockProps {
  code: string;
  language?: string;
  title?: string;
  showLineNumbers?: boolean;
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function highlightLine(line: string): string {
  if (line.trim().startsWith('//') || line.trim().startsWith('#')) {
    return `<span style="color:#6B9967">${escapeHtml(line)}</span>`;
  }
  if (line.trim().startsWith('*') || line.trim().startsWith('/**') || line.trim().startsWith('*/')) {
    return `<span style="color:#6B9967">${escapeHtml(line)}</span>`;
  }

  let result = escapeHtml(line);
  const kws = ['public','private','protected','class','interface','enum','record','extends','implements',
    'new','return','import','package','static','final','void','boolean','int','long','byte',
    'throws','try','catch','throw','if','else','for','while','null','true','false','var','sealed',
    'permits','default','switch','case','break','continue','instanceof','super','this','abstract',
    'synchronized','volatile','transient','native','strictfp'];
  kws.forEach(kw => {
    result = result.replace(new RegExp(`\\b${kw}\\b`, 'g'), `<span style="color:#C792EA">${kw}</span>`);
  });
  result = result.replace(/(&quot;[^&]*&quot;)/g, '<span style="color:#C3E88D">$1</span>');
  result = result.replace(/(\/\/[^<]*)/, '<span style="color:#6B9967">$1</span>');
  result = result.replace(/\b(\d+(?:\.\d+)?)\b/g, '<span style="color:#F78C6C">$1</span>');
  result = result.replace(/(@\w+)/g, '<span style="color:#FFCB6B">$1</span>');
  result = result.replace(/\b(String|Integer|Long|Boolean|Object|List|Map|Set|Optional|CompletableFuture)\b/g,
    '<span style="color:#82AAFF">$1</span>');
  return result;
}

export function CodeBlock({ code, language = 'java', title, showLineNumbers = true }: CodeBlockProps) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    await navigator.clipboard.writeText(code);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const lines = code.trim().split('\n');
  const isXml = language === 'xml' || language === 'maven';
  const isYaml = language === 'yaml' || language === 'yml';
  const isBash = language === 'bash' || language === 'shell';

  return (
    <div className="rounded-xl overflow-hidden border border-slate-200 dark:border-slate-700 my-4 shadow-sm">
      <div className="flex items-center justify-between px-4 py-2 bg-slate-800 dark:bg-slate-900 border-b border-slate-700">
        <span className="text-xs font-medium text-slate-400 font-mono">{title || ''}</span>
        <div className="flex items-center gap-3">
          <span className="text-xs text-slate-500 uppercase tracking-wider">{language}</span>
          <button
            onClick={handleCopy}
            className="p-1 rounded text-slate-400 hover:text-white transition-colors"
            aria-label="Copy code"
          >
            {copied ? <Check size={13} /> : <Copy size={13} />}
          </button>
        </div>
      </div>
      <div className="overflow-x-auto bg-slate-900 dark:bg-slate-950">
        <pre className="p-4 text-sm leading-relaxed">
          <code className="text-slate-100 font-mono">
            {lines.map((line, i) => (
              <div key={i} className="flex min-w-0">
                {showLineNumbers && (
                  <span className="select-none text-slate-600 text-right mr-4 min-w-[2rem] flex-shrink-0 tabular-nums">
                    {i + 1}
                  </span>
                )}
                {(isXml || isYaml || isBash)
                  ? <span className="text-slate-200">{line}</span>
                  : <span dangerouslySetInnerHTML={{ __html: highlightLine(line) }} />
                }
              </div>
            ))}
          </code>
        </pre>
      </div>
    </div>
  );
}
