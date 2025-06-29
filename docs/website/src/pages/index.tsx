import React from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';
import styles from './index.module.css';

// Type definitions for Docusaurus site config
type SiteConfig = {
  title: string;
  tagline: string;
  // Add other site config properties as needed
};

// Type for the Docusaurus context
type DocusaurusContext = {
  siteConfig: SiteConfig;
  // Add other context properties as needed
};

const HomepageHeader: React.FC = () => {
  const { siteConfig } = useDocusaurusContext() as DocusaurusContext;
  return (
    <header className={clsx('hero hero--primary', styles.heroBanner)}>
      <div className="container">
        <div className="row">
          <div className="col col--8 col--offset-2">
            <div className={styles.heroContent}>
              <div className={styles.logoContainer}>
                <img 
                  src="/img/Ligero.svg" 
                  alt="Ligero Logo" 
                  className={styles.logo}
                />
              </div>
              <Heading as="h1" className="hero__title">
                {siteConfig.title}
              </Heading>
              <p className="hero__subtitle">{siteConfig.tagline}</p>
              <div className={styles.buttons}>
                <Link
                  className="button button--secondary button--lg"
                  to="/category/getting-started">
                  Get Started
                </Link>
                <Link
                  className="button button--outline button--lg"
                  href="https://github.com/your-org/ligero">
                  GitHub
                </Link>
              </div>
            </div>
          </div>
        </div>
      </div>
    </header>
  );
}

interface FeatureProps {
  title: string;
  description: string;
}

const Feature: React.FC<FeatureProps> = ({ title, description }) => {
  return (
    <div className="col col--4 padding-horiz--md">
      <div className="text--center">
        <h3>{title}</h3>
        <p>{description}</p>
      </div>
    </div>
  );
}

const HomepageFeatures: React.FC = () => {
  return (
    <section className={styles.features}>
      <div className="container">
        <div className="row">
          <Feature
            title="Lightweight"
            description="Built with minimal dependencies for fast startup and low memory footprint."
          />
          <Feature
            title="Simple API"
            description="Intuitive and easy-to-learn API that gets out of your way."
          />
          <Feature
            title="Modular"
            description="Use only what you need and keep your application lean."
          />
        </div>
      </div>
    </section>
  );
}

const Home: React.FC = () => {
  const { siteConfig } = useDocusaurusContext() as DocusaurusContext;
  
  return (
    <Layout
      title="Home"
      description="Lightweight Java Web Framework">
      <HomepageHeader />
      <main>
        <HomepageFeatures />
        <section className="padding-vert--xl">
          <div className="container">
            <div className="row">
              <div className="col col--8 col--offset-2">
                <div className="text--center">
                  <h2>Get Started in Minutes</h2>
                  <div className="margin-vert--lg">
                    <pre className={styles.codeBlock}>
                      <code>{
                        '// Create a simple server\n' +
                        'Ligero app = Ligero.create(8080);\n\n' +
                        '// Define a route\n' +
                        'app.get("/", (req, res) -> {\n' +
                        '  res.send("Hello, Ligero!");\n' +
                        '});\n\n' +
                        '// Start the server\n' +
                        'app.start();'
                      }</code>
                    </pre>
                  </div>
                  <Link
                    className="button button--primary button--lg"
                    to="/docs/getting-started/quick-start">
                    Read the Documentation
                  </Link>
                </div>
              </div>
            </div>
          </div>
        </section>
      </main>
    </Layout>
  );
}


export default Home;
