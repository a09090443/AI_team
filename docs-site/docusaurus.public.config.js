import internal from './docusaurus.config.js';

// 公開版僅載入明確審閱過的 public-docs，不載入內部文件、頁面或全文索引。
export default {
  ...internal,
  title: 'AI Team',
  tagline: '以 Slack 協作的 AI 軟體團隊',
  staticDirectories: [],
  presets: [['classic', {
    docs: { path: 'public-docs', routeBasePath: '/', sidebarPath: './sidebars-public.js' },
    pages: { path: 'public-pages' },
    blog: false,
    theme: { customCss: './src/css/public.css' },
  }]],
  plugins: [],
  themes: ['./public-theme'],
  themeConfig: {
    navbar: { title: 'AI Team', items: [
      { to: '/intro/', label: '文件', position: 'left' },
      { href: 'https://github.com/a09090443/AI_team', label: 'GitHub', position: 'right' },
      { type: 'search', position: 'right' },
    ] },
    footer: { style: 'dark', copyright: `Copyright © ${new Date().getFullYear()} AI Team. Built with Docusaurus.` },
  },
};
