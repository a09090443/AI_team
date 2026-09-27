// build 完成後產生給 AI agent 用的機器可讀檔案：
//   /llms.txt       分類索引（llmstxt.org 格式），每份文件附狀態與用途
//   /llms-full.txt  agent 入口頁＋所有「現行」文件的全文
//   /<路由>.md      每份文件的 Markdown 原文，例如 /docs/INSTALL.md
import fs from 'node:fs/promises';
import path from 'node:path';
import { categories, docs, statuses } from '../catalog.mjs';

const stripFrontMatter = (text) => text.replace(/^---\r?\n[\s\S]*?\r?\n---\r?\n/, '').trimStart();

async function agentPages(agentDir) {
  const pages = [];
  for (const file of await fs.readdir(agentDir)) {
    if (!file.endsWith('.md')) continue;
    const raw = await fs.readFile(path.join(agentDir, file), 'utf8');
    const front = raw.match(/^---\r?\n([\s\S]*?)\r?\n---/)?.[1] ?? '';
    const field = (name) => front.match(new RegExp(`^${name}:\\s*(.+)$`, 'm'))?.[1].trim();
    const id = file.replace(/\.md$/, '');
    pages.push({
      id,
      route: id === 'index' ? 'agent/index' : `agent/${id}`,
      title: field('title') ?? id,
      description: field('description') ?? '',
      position: Number(field('sidebar_position') ?? 99),
      body: stripFrontMatter(raw),
    });
  }
  return pages.sort((a, b) => a.position - b.position);
}

export default function llmsTxtPlugin(context, options) {
  const docsDir = path.resolve(context.siteDir, options.docsDir);
  const agentDir = path.resolve(context.siteDir, options.agentDir);

  return {
    name: 'llms-txt',
    async postBuild({ outDir, siteConfig }) {
      const base = `${siteConfig.url.replace(/\/$/, '')}${siteConfig.baseUrl}`;
      const agent = await agentPages(agentDir);

      const lines = [
        `# ${siteConfig.title}`,
        '',
        `> ${options.summary}`,
        '',
        '文件狀態：' + Object.values(statuses).map((s) => `「${s.label}」${s.description}`).join('；') + '。',
        '「歷史」文件已被取代，不可照做；同一文件內以日期最新的段落為準；文件與程式不一致時以程式為準。',
        '',
        '## AI Agent 入口',
        '',
        ...agent.map((page) => `- [${page.title}](${base}${page.route}.md): ${page.description}`),
      ];

      for (const category of categories) {
        const items = docs.filter((doc) => doc.category === category.id);
        if (items.length === 0) continue;
        lines.push('', `## ${category.id === 'history' ? 'Optional' : category.label}`, '');
        if (category.id === 'history') lines.push(`${category.label}：${category.description}`, '');
        for (const doc of items) {
          const when = doc.readWhen ? ` 何時讀：${doc.readWhen}` : '';
          lines.push(`- [${doc.title}](${base}docs/${doc.id}.md): 〔${statuses[doc.status].label}〕${doc.summary}${when}`);
        }
      }
      await fs.writeFile(path.join(outDir, 'llms.txt'), lines.join('\n') + '\n');

      const full = [`# ${siteConfig.title}（全文）`, '', `> ${options.summary}`, ''];
      for (const page of agent) full.push('---', '', `<!-- source: docs-site/agent/${page.id}.md -->`, '', page.body.trim(), '');
      for (const doc of docs.filter((d) => d.status === 'current')) {
        const body = await fs.readFile(path.join(docsDir, `${doc.id}.md`), 'utf8');
        full.push('---', '', `<!-- source: docs/${doc.id}.md（${statuses[doc.status].label}）-->`, '', stripFrontMatter(body).trim(), '');
      }
      await fs.writeFile(path.join(outDir, 'llms-full.txt'), full.join('\n'));

      const copies = [
        ...docs.map((doc) => [path.join(docsDir, `${doc.id}.md`), `docs/${doc.id}.md`]),
        ...agent.map((page) => [path.join(agentDir, `${page.id}.md`), `${page.route}.md`]),
      ];
      for (const [from, to] of copies) {
        const target = path.join(outDir, to);
        await fs.mkdir(path.dirname(target), { recursive: true });
        await fs.copyFile(from, target);
      }
    },
  };
}
