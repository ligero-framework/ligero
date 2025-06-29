import { themes as prismThemes } from 'prism-react-renderer';
import type { Config } from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

/**
 * Docusaurus configuration for Ligero Framework documentation.
 * See: https://docusaurus.io/docs/configuration
 */
const config: Config = {
  title: 'Ligero Framework',
  tagline: 'Lightweight Java Web Framework',
  favicon: 'img/favicon.ico',

  // Future flags for Docusaurus
  future: {
    v4: true,
  },

  // Production URL configuration
  url: 'https://your-docusaurus-site.example.com',
  baseUrl: '/',

  // Deployment configuration
  organizationName: 'your-org', // GitHub org/user name
  projectName: 'ligero', // GitHub repo name
  deploymentBranch: 'gh-pages',
  trailingSlash: false,

  // Error handling
  onBrokenLinks: 'throw',
  onBrokenMarkdownLinks: 'warn',

  // Internationalization
  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      {
        docs: {
          sidebarPath: './sidebars.ts',
          editUrl: 'https://github.com/your-org/ligero/edit/main/docs/website/',
          routeBasePath: '/', // Serve the docs at the site's root
        },
        blog: false, // Disable blog
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    // Project metadata
    image: 'img/ligero-social-card.jpg',
    navbar: {
      title: 'Ligero',
      logo: {
        alt: 'Ligero Logo',
        src: 'img/Ligero.svg',
      },
      items: [
        {
          type: 'docSidebar',
          sidebarId: 'docs',
          position: 'left',
          label: 'Documentation',
        },
        {
          href: 'https://github.com/your-org/ligero',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Docs',
          items: [
            {
              label: 'Getting Started',
              to: '/getting-started/introduction',
            },
            {
              label: 'API Reference',
              to: '/api',
            },
          ],
        },
        {
          title: 'Community',
          items: [
            {
              label: 'GitHub',
              href: 'https://github.com/your-org/ligero',
            },
            {
              label: 'Issues',
              href: 'https://github.com/your-org/ligero/issues',
            },
            {
              label: 'Discussions',
              href: 'https://github.com/your-org/ligero/discussions',
            },
          ],
        },
      ],
      copyright: `Copyright ${new Date().getFullYear()} Ligero Framework. Built with Docusaurus.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['java', 'bash', 'json'],
    },
    docs: {
      sidebar: {
        hideable: true,
      },
    },
  } satisfies Config['themeConfig'],
};

export default config;
