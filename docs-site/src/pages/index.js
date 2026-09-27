import Link from '@docusaurus/Link';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';
import './index.css';

const sections = [
  {
    number: '01',
    title: '從零建置',
    description: '新環境前置條件、工具鏈、CLI 登入與安裝步驟。',
    to: '/docs/INSTALL',
    meta: 'INSTALLATION',
  },
  {
    number: '02',
    title: '系統架構',
    description: '協調器、Slack、執行隔離與 Agent Runtime 的責任邊界。',
    to: '/agent/system-map',
    meta: 'SYSTEM DESIGN',
  },
  {
    number: '03',
    title: '工程流程',
    description: '需求追溯、實作、獨立驗證、審查與發布狀態。',
    to: '/docs/engineering-workflow',
    meta: 'DELIVERY FLOW',
  },
  {
    number: '04',
    title: 'QA 與分流',
    description: '情境驗收、受阻發現、返工與真人決策的處理方式。',
    to: '/docs/qa-scenario-workflow',
    meta: 'QUALITY',
  },
  {
    number: '05',
    title: '配置式團隊',
    description: '團隊定義、角色設定、流程節點與舊任務相容性。',
    to: '/docs/configurable-team',
    meta: 'TEAM CONFIG',
  },
  {
    number: '06',
    title: '部署與驗證',
    description: 'Linux 支援狀態、重建證據、部署紀錄與已知缺口。',
    to: '/docs/rebuild-verification',
    meta: 'OPERATIONS',
  },
];

export default function Home() {
  return (
    <Layout title="文件入口" description="AI Team 系統、流程與維運文件">
      <main className="field-guide">
        <section className="guide-heading">
          <div>
            <p className="eyebrow">ENGINEERING HANDBOOK <span>·</span> 2026</p>
            <Heading as="h1">AI Team<br /><span>文件入口</span></Heading>
            <p className="intro">
              系統設計、工程流程與維運紀錄。從建置指南開始，依主題查閱最新操作契約與驗證證據。
            </p>
          </div>
          <aside className="status-panel" aria-label="專案狀態">
            <div className="status-topline"><span className="status-dot" /> PROJECT STATUS</div>
            <strong>開發中</strong>
            <p>乾淨 VM 重建驗收尚未通過</p>
            <Link to="/docs/rebuild-verification" className="status-link">
              查看驗證缺口 <span aria-hidden="true">↗</span>
            </Link>
          </aside>
        </section>

        <section className="start-strip" aria-label="AI Agent 入口">
          <span className="start-index">START HERE / 00</span>
          <div>
            <h2>第一次接觸這個專案？</h2>
            <p>AI agent 與新維護者先讀入口頁：專案全貌、閱讀規則、操作指令與依任務找文件。機器可讀索引在 /llms.txt。</p>
          </div>
          <Link to="/agent/" className="start-link">
            開啟 AI Agent 入口 <span aria-hidden="true">→</span>
          </Link>
        </section>

        <section className="library" aria-labelledby="library-title">
          <div className="section-heading">
            <div>
              <p className="eyebrow">DOCUMENT LIBRARY</p>
              <Heading as="h2" id="library-title">依工作主題瀏覽</Heading>
            </div>
            <Link to="/catalog" className="all-docs">完整文件目錄 <span aria-hidden="true">↗</span></Link>
          </div>
          <div className="section-grid">
            {sections.map((section) => (
              <Link className="section-link" to={section.to} key={section.number}>
                <div className="section-meta"><span>{section.number}</span><span>{section.meta}</span></div>
                <h3>{section.title}</h3>
                <p>{section.description}</p>
                <span className="section-arrow" aria-hidden="true">↗</span>
              </Link>
            ))}
          </div>
        </section>

        <footer className="guide-footer">
          <span>AI TEAM / INTERNAL DOCUMENTATION</span>
          <span>文件中的實測狀態以各紀錄日期為準</span>
        </footer>
      </main>
    </Layout>
  );
}
