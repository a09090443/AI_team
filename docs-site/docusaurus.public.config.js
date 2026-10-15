import internal from './docusaurus.config.js';

// 公開版僅載入明確審閱過的 public-docs，不載入內部文件、頁面或全文索引。
export default {
  ...internal,
  title: 'AI Team',
  tagline: '以 Slack 協作的 AI 軟體團隊',
  staticDirectories: [],
  presets: [['classic', {
    docs: { path: 'public-docs', routeBasePath: '/', sidebarPath: false },
    pages: false,
    blog: false,
    theme: { customCss: './src/css/custom.css' },
  }]],
  plugins: [],
  themeConfig: {
    navbar: { title: 'AI Team', items: [
      { to: '/', label: '介紹', position: 'right' },
      { to: '/architecture/', label: '架構', position: 'right' },
      { to: '/workflow/', label: '工作流程', position: 'right' },
    ] },
    footer: { style: 'dark', copyright: `AI Team · ${new Date().getFullYear()}` },
  },
};
