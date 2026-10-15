import React from 'react';
import Layout from '@theme/Layout';
import Link from '@docusaurus/Link';

const features = [
  {title: '多角色協作', description: '透過 Slack 提出需求，讓不同角色的 AI agents 分工處理設計、實作、測試與程式審查。', kind: 'team'},
  {title: '專注開發流程', description: '以配置式流程連結需求、驗收條件與驗證結果，在重要決策與發布階段保留真人核准。', kind: 'flow'},
  {title: '任務持續接力', description: '保存工作進度與結構化交接摘要，協助不同工具與 agents 接續長任務。', kind: 'handoff'},
];
function Illustration({kind}) {
  return <svg className="feature-illustration" viewBox="0 0 240 180" aria-hidden="true">
    <circle cx="120" cy="85" r="75" fill="#f0f1f4" />
    {kind === 'team' ? <>
      <path d="M45 145L95 50L125 105L150 70L200 145Z" fill="#34334f" />
      <path d="M78 83L95 50L113 83L103 77L96 85L87 77Z" fill="white" />
      {[78,120,162].map((x,i) => <g key={x}><rect x={x-16} y={104-i%2*14} width="32" height="38" rx="8" fill="#2e8555"/><circle cx={x-5} cy={116-i%2*14} r="3" fill="white"/><circle cx={x+5} cy={116-i%2*14} r="3" fill="white"/></g>)}
    </> : kind === 'flow' ? <>
      <rect x="35" y="45" width="170" height="105" rx="8" fill="#34334f"/>
      <rect x="47" y="58" width="146" height="78" rx="3" fill="#222338"/>
      {[72,98,124].map((y,i) => <g key={y}><circle cx="66" cy={y} r="6" fill="#39bd78"/><path d={`M82 ${y}H${170-i*18}`} stroke="#39bd78" strokeWidth="5"/></g>)}
      <path d="M22 153H218" stroke="#34334f" strokeWidth="7" strokeLinecap="round"/>
    </> : <>
      <rect x="32" y="65" width="72" height="60" rx="5" fill="#34334f"/><rect x="136" y="65" width="72" height="60" rx="5" fill="#34334f"/>
      <path d="M104 94H136M128 86L136 94L128 102" stroke="#2e8555" strokeWidth="5" fill="none"/>
      <rect x="45" y="78" width="46" height="33" rx="3" fill="#39bd78"/><rect x="149" y="78" width="46" height="33" rx="3" fill="#39bd78"/>
      <path d="M68 125V144M172 125V144M48 146H88M152 146H192" stroke="#34334f" strokeWidth="6"/>
    </>}
  </svg>;
}
export default function Home() {
  return <Layout title="AI Team" description="以 Slack 協作的 AI 軟體團隊">
    <header className="hero hero--primary public-hero"><div className="container">
      <h1 className="hero__title">AI Team</h1>
      <p className="hero__subtitle">以 Slack 為介面，讓 AI agents 協作完成軟體開發任務</p>
      <Link className="button button--secondary button--lg" to="/intro/">開始閱讀文件 📚</Link>
    </div></header>
    <main><section className="public-features" aria-label="專案特色"><div className="container"><div className="row">
      {features.map(f => <div className="col col--4" key={f.kind}><Illustration kind={f.kind}/><h2>{f.title}</h2><p>{f.description}</p></div>)}
    </div></div></section></main>
  </Layout>;
}
