// @docusaurus/theme-mermaid 3.10 會在打包時解析選用的 @mermaid-js/layout-elk（即使執行時不啟用）。
// 本站不使用 ELK 版面，將它對應成空模組，避免為此安裝較大的依賴。
export default function mermaidNoElk() {
  return {
    name: 'mermaid-no-elk',
    configureWebpack() {
      return { resolve: { alias: { '@mermaid-js/layout-elk': false } } };
    },
  };
}
