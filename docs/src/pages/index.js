import React from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';

function HeroSection() {
  const {siteConfig} = useDocusaurusContext();
  return (
    <header className="hero hero--primary hero-banner">
      <div className="container">
        <div className="hero-logo-container">
          <img src="img/logo.png" alt="Veridot Logo" className="hero-logo" />
        </div>
        <Heading as="h1" className="hero__title gradient-text">
          {siteConfig.title}
        </Heading>
        <p className="hero__subtitle subtitle-text">{siteConfig.tagline}</p>
        <div className="hero-buttons">
          <Link
            className="button button--secondary button--lg hero-btn-primary"
            to="/docs/learn/the-problem">
            Start Learning 📖
          </Link>
          <Link
            className="button button--outline button--lg hero-btn-secondary"
            to="/docs/api/">
            API Reference
          </Link>
        </div>
      </div>
    </header>
  );
}

const FeatureList = [
  {
    title: '⚡ Sub-Millisecond Verification',
    description: (
      <>
        Verifier nodes query their local RocksDB instances to validate tokens in under 1 millisecond. No network hops, no bottlenecks.
      </>
    ),
  },
  {
    title: '🔒 Zero Shared Secrets',
    description: (
      <>
        Instead of vulnerable shared HMAC keys, Veridot generates ephemeral Ed25519 asymmetric key pairs dynamically per session.
      </>
    ),
  },
  {
    title: '🔄 Instant Revocation',
    description: (
      <>
        Revocation requests propagate asynchronously via Kafka/SQL brokers to update local verifier caches in under 1 second.
      </>
    ),
  },
];

function Feature({title, description}) {
  return (
    <div className={clsx('col col--4')}>
      <div className="card feature-card">
        <div className="card__header">
          <Heading as="h3">{title}</Heading>
        </div>
        <div className="card__body">
          <p>{description}</p>
        </div>
      </div>
    </div>
  );
}

function FeaturesSection() {
  return (
    <section className="features-section">
      <div className="container">
        <div className="row">
          {FeatureList.map((props, idx) => (
            <Feature key={idx} {...props} />
          ))}
        </div>
      </div>
    </section>
  );
}

function CodeShowcaseSection() {
  return (
    <section className="showcase-section">
      <div className="container">
        <Heading as="h2" className="text-center section-title">Simple and Powerful Java API</Heading>
        <div className="row showcase-row">
          <div className="col col--6">
            <Heading as="h3">1. Sign a Token</Heading>
            <pre className="code-block-showcase">
{`String token = signer.sign(
  new UserPayload("alice", "admin"),
  BasicConfigurer.builder()
    .groupId("user-alice")
    .sequenceId("session-1")
    .validity(3600) // 1 hour
    .build()
);`}
            </pre>
          </div>
          <div className="col col--6">
            <Heading as="h3">2. Verify In Sub-ms</Heading>
            <pre className="code-block-showcase">
{`VerifiedData<UserPayload> verified = verifier.verify(
  token,
  BasicConfigurer.deserializer(UserPayload.class)
);

System.out.println(verified.data().username()); // "alice"`}
            </pre>
          </div>
        </div>
      </div>
    </section>
  );
}

export default function Home() {
  const {siteConfig} = useDocusaurusContext();
  return (
    <Layout
      title={`${siteConfig.title} — Distributed Token Verification`}
      description="Distributed token verification protocol for microservices. Sub-ms verification, instant revocation, zero shared secrets.">
      <HeroSection />
      <main>
        <FeaturesSection />
        <CodeShowcaseSection />
      </main>
    </Layout>
  );
}
