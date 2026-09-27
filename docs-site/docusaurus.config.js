// 非 SPA 路由的靜態檔（llms.txt 等）用 pathname:// 讓 Docusaurus 不做路由檢查
const pathname = (p) => `pathname://${p}`;

const config = {
  title: 'AI Team 文件',
  tagline: '給 AI agent 與維護者的系統、流程與維運手冊',
  url: 'http://localhost',
  baseUrl: '/',
  onBrokenLinks: 'throw',
  markdown: {
    format: 'md',
    hooks: {
      onBrokenMarkdownLinks: 'warn',
    },
  },
  i18n: {
    defaultLocale: 'zh-Hant',
    locales: ['zh-Hant'],
  },
  presets: [
    [
      'classic',
      {
        docs: {
          path: 'docs',
          routeBasePath: 'docs',
          sidebarPath: './sidebars.js',
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      },
    ],
  ],
  plugins: [
    [
      '@docusaurus/plugin-content-docs',
      {
        id: 'agent',
        path: 'agent',
        routeBasePath: 'agent',
        sidebarPath: './sidebars-agent.js',
      },
    ],
    [
      './plugins/llms-txt.mjs',
      {
        docsDir: 'docs',
        agentDir: 'agent',
        summary:
          'AI Team：以 Slack 為介面的 AI 軟體團隊協調器（Java 21／Spring Boot）。依團隊配置 JSON 派 Claude Code、Codex 等 agent CLI 分階段完成需求、設計、測試計畫、實作、驗收與審查，真人核准後開 GitHub PR。目前尚未通過乾淨 VM 重建驗收。',
      },
    ],
  ],
  themeConfig: {
    navbar: {
      title: 'AI TEAM / FIELD GUIDE',
      items: [
        {
          type: 'docSidebar',
          sidebarId: 'docs',
          position: 'right',
          label: '文件索引',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: '快速入口',
          items: [
            { label: 'AI Agent 入口', to: '/agent/' },
            { label: '文件目錄', to: '/catalog' },
            { label: '從零建置', to: '/docs/INSTALL' },
            { label: '工程流程', to: '/docs/engineering-workflow' },
            { label: 'QA 流程', to: '/docs/qa-scenario-workflow' },
          ],
        },
      ],
      copyright: `AI Team · ${new Date().getFullYear()}`,
    },
  },
};

export default config;
