import { useEffect, useState } from 'react';
import { useApp } from '../context/AppContext';
import mermaid from 'mermaid';

interface MermaidProps {
  chart: string;
  caption?: string;
}

let mermaidInitialized = false;

export function Mermaid({ chart, caption }: MermaidProps) {
  const { theme } = useApp();
  const [svg, setSvg] = useState<string>('');
  const [error, setError] = useState<boolean>(false);

  useEffect(() => {
    let isMounted = true;
    
    const renderChart = async () => {
      try {
        if (!mermaidInitialized) {
          mermaid.initialize({
            startOnLoad: false,
            theme: theme === 'dark' ? 'dark' : 'default',
            fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
            fontSize: 13,
            securityLevel: 'loose',
          });
          mermaidInitialized = true;
        } else {
          mermaid.initialize({
            startOnLoad: false,
            theme: theme === 'dark' ? 'dark' : 'default',
          });
        }
        
        // Generate a clean, unique ID for this specific render pass
        const uniqueId = `mermaid-${Math.random().toString(36).substring(2, 9)}`;
        
        const { svg: svgCode } = await mermaid.render(uniqueId, chart);
        
        if (isMounted) {
          setSvg(svgCode);
          setError(false);
        }
      } catch (e) {
        console.error("Mermaid rendering error:", e);
        if (isMounted) {
          setError(true);
        }
      }
    };

    renderChart();

    return () => {
      isMounted = false;
    };
  }, [chart, theme]);

  return (
    <figure className="my-6">
      <div className="flex justify-center p-4 bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-700 overflow-x-auto min-h-[100px] items-center">
        {error ? (
          <pre className="text-xs text-red-500 p-4 font-mono select-all bg-red-50 dark:bg-red-950/20 rounded-lg w-full overflow-x-auto">
            {chart}
          </pre>
        ) : svg ? (
          <div 
            className="w-full flex justify-center mermaid-svg-container" 
            dangerouslySetInnerHTML={{ __html: svg }} 
          />
        ) : (
          <div className="text-xs text-slate-400 dark:text-slate-500 py-4 animate-pulse">
            Rendering diagram...
          </div>
        )}
      </div>
      {caption && (
        <figcaption className="text-center text-xs text-slate-500 dark:text-slate-400 mt-2 italic">
          {caption}
        </figcaption>
      )}
    </figure>
  );
}
