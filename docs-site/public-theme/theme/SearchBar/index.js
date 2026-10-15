import React, {useState} from 'react';
import Link from '@docusaurus/Link';

const pages = [
  {title: 'AI Team 介紹', url: '/intro/', keywords: 'Slack Java Spring Boot 角色 協作'},
  {title: '架構概要', url: '/architecture/', keywords: 'orchestrator runtime CLI 配置 團隊'},
  {title: '工作流程', url: '/workflow/', keywords: '需求 設計 測試 驗收 審查 交接 核准'},
];
export default function SearchBar() {
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const results = pages.filter(p => `${p.title} ${p.keywords}`.toLowerCase().includes(query.trim().toLowerCase()));
  return <div className="public-search" onBlur={e => {if (!e.currentTarget.contains(e.relatedTarget)) setOpen(false);}}>
    <input type="search" aria-label="搜尋公開文件" placeholder="搜尋文件…" value={query}
      onFocus={() => setOpen(true)} onChange={e => {setQuery(e.target.value); setOpen(true);}}
      onKeyDown={e => {if(e.key === 'Escape') setOpen(false);}} />
    {open && query.trim() && <div className="public-search-results" aria-live="polite">
      {results.length ? results.map(p => <Link key={p.url} to={p.url} onClick={() => setOpen(false)}>{p.title}</Link>) : <span>找不到符合的公開文件</span>}
    </div>}
  </div>;
}
