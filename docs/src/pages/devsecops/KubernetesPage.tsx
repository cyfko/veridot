import { useApp } from '../../context/AppContext';
import { CodeBlock } from '../../components/CodeBlock';
import { Admonition } from '../../components/Admonition';

const YAML_SAMPLE = `apiVersion: apps/v1
kind: Deployment
metadata:
  name: veridot-verifier
spec:
  replicas: 3
  template:
    spec:
      containers:
      - name: app
        env:
        - name: VDOT_WATERMARK_PERSISTENCE_FILE
          value: "/var/lib/veridot/watermark.snap"
        volumeMounts:
        - name: watermark-storage
          mountPath: /var/lib/veridot
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
      volumes:
      - name: watermark-storage
        persistentVolumeClaim:
          claimName: watermark-pvc`;

export function KubernetesPage() {
  const { language } = useApp();

  return (
    <div className="space-y-8">
      <div>
        <p className="text-sm font-medium text-violet-600 dark:text-violet-400 mb-2">DevSecOps</p>
        <h1 className="text-3xl font-bold text-slate-900 dark:text-white mb-3">Kubernetes Deployment</h1>
        <p className="text-lg text-slate-600 dark:text-slate-400">
          {language === 'en'
            ? 'Deploy Veridot verifiers in Kubernetes with high availability, configured health checks, and persistent volumes for version watermarks.'
            : 'Déployez les vérificateurs Veridot sur Kubernetes avec haute disponibilité et volumes persistants.'}
        </p>
      </div>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Persistent Storage for Watermarks' : 'Stockage Persistant pour les Filigranes'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 leading-relaxed mb-3">
          {language === 'en'
            ? 'Verifiers require local storage to persist version watermarks. If watermarks are lost, verifiers fail-closed, rejecting active sessions. Mount an encrypted PersistentVolume (PV) to store the watermark.snap file:'
            : 'Les vérificateurs nécessitent un stockage local persistant pour leurs filigranes. Montez un PersistentVolume (PV) chiffré pour conserver le fichier watermark.snap :'}
        </p>
        <CodeBlock code={YAML_SAMPLE} language="yaml" title="Kubernetes deployment specification" />
      </section>

      <section>
        <h2 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
          {language === 'en' ? 'Health Check Probes' : 'Sondes de Santé (Probes)'}
        </h2>
        <p className="text-slate-600 dark:text-slate-400 leading-relaxed">
          {language === 'en'
            ? 'Set up readiness probes that monitor the state of the local cache. If the local cache staleness (veridot_watermark_staleness_ms) exceeds 60 minutes, the pod should be flagged as unready, removing it from active traffic.'
            : 'Configurez des readiness probes pour surveiller la réconciliation du cache local. Si le retard dépasse 60 minutes, le pod doit être exclu du trafic HTTP.'}
        </p>
      </section>

      <Admonition type="warning" title={language === 'en' ? 'Stateless Restarts Warning' : 'Avertissement sur les Redémarrages Stateless'}>
        {language === 'en'
          ? 'Never run Veridot verifiers with emptyDir or ephemeral storage for watermarks. A stateless restart allows attackers to execute state rollback and token replay attacks.'
          : 'N\'utilisez jamais de volume emptyDir ou de stockage éphémère pour les filigranes de version sous peine d\'exposer le nœud aux attaques par rejeu.'}
      </Admonition>
    </div>
  );
}
