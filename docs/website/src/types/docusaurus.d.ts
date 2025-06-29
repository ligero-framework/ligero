import type { DocusaurusConfig } from '@docusaurus/types';

declare module '@docusaurus/useDocusaurusContext' {
  export interface DocusaurusContext {
    siteConfig: DocusaurusConfig & {
      title: string;
      tagline: string;
      // Add other custom site config properties here
    };
    // Add other context properties as needed
  }

  export default function useDocusaurusContext(): DocusaurusContext;
}

// Add other module augmentations as needed
