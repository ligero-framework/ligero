// @ts-check
/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'Ligero Framework',
  tagline: 'A lightweight Java web framework for modern applications',
  url: 'https://ligero-framework.github.io',
  baseUrl: '/ligero-docs/',
  organizationName: 'ligero-framework',
  projectName: 'ligero-docs',
  trailingSlash: false,
  onBrokenLinks: 'warn',
  favicon: 'img/Ligero.svg',
  i18n: {
    defaultLocale: 'en',
    locales: ['en'], // 'es' translation planned via Docusaurus i18n
  },
  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          routeBasePath: '/',
          sidebarPath: './sidebars.js',
          editUrl: 'https://github.com/ligero-framework/ligero-docs/edit/main/',
        },
        blog: false,
        theme: { customCss: './src/css/custom.css' },
      }),
    ],
  ],
  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      navbar: {
        title: 'Ligero',
        logo: { alt: 'Ligero', src: 'img/Ligero.svg' },
        items: [
          { type: 'docSidebar', sidebarId: 'docs', position: 'left', label: 'Docs' },
          { href: 'https://github.com/ligero-framework/ligero', label: 'GitHub', position: 'right' },
        ],
      },
      footer: {
        style: 'dark',
        copyright: `Apache 2.0 — Ligero Framework`,
      },
      prism: {
        additionalLanguages: ['java', 'groovy', 'bash'],
      },
    }),
};

export default config;
