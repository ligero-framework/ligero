import type { SidebarsConfig } from '@docusaurus/plugin-content-docs';

/**
 * Sidebar configuration for the documentation.
 * See: https://docusaurus.io/docs/sidebar
 */
const sidebars: SidebarsConfig = {
  // Main sidebar for documentation
  docs: [
    'intro',  // Changed from 'introduction' to 'intro' to match the actual file
    {
      type: 'category',
      label: 'Getting Started',
      link: {
        type: 'generated-index',
        title: 'Getting Started',
        description: 'Learn how to get started with Ligero Framework',
      },
      items: [
        'getting-started/installation',
        'getting-started/quick-start',
        'getting-started/core-concepts',
      ],
    },
    {
      type: 'category',
      label: 'Guides',
      link: {
        type: 'generated-index',
        title: 'Guides',
        description: 'Learn how to build with Ligero Framework',
      },
      items: [
        'guides/routing',
        'guides/rest-api-tutorial',
        'guides/routes-controllers',
        // Removed non-existent guides for now
      ],
    },
    // Removed API Reference section as those files don't exist yet
  ],
};

export default sidebars;
