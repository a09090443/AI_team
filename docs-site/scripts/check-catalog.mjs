// 文件一致性檢查，build 前自動執行，失敗時結束碼 1：
// 1. catalog.mjs 與 docs-site/docs/ 一致：每份 Markdown 都有登錄、登錄的文件都存在、欄位合法、相關程式路徑存在。
// 2. agent/project-tree.md 的目錄樹與實際檔案一致：列出的路徑存在，涵蓋範圍內的檔案都有列出。
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { categories, docs, statuses } from '../catalog.mjs';

const siteDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repoDir = path.resolve(siteDir, '..');
const docsDir = path.join(siteDir, 'docs');

const listMarkdown = (dir, prefix = '') =>
  fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const rel = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isDirectory()) return listMarkdown(path.join(dir, entry.name), rel);
    return /\.mdx?$/.test(entry.name) ? [rel.replace(/\.mdx?$/, '')] : [];
  });

const errors = [];
const onDisk = new Set(listMarkdown(docsDir));
const categoryIds = new Set(categories.map((c) => c.id));
const seen = new Set();

for (const doc of docs) {
  if (seen.has(doc.id)) errors.push(`重複登錄：${doc.id}`);
  seen.add(doc.id);
  if (!onDisk.has(doc.id)) errors.push(`catalog 登錄了不存在的文件：docs/${doc.id}.md`);
  if (!categoryIds.has(doc.category)) errors.push(`${doc.id}：未知分類 ${doc.category}`);
  if (!statuses[doc.status]) errors.push(`${doc.id}：未知狀態 ${doc.status}`);
  if (!doc.title || !doc.summary) errors.push(`${doc.id}：缺少 title 或 summary`);
  if (doc.status !== 'historical' && !doc.readWhen) errors.push(`${doc.id}：非歷史文件需要 readWhen`);
  for (const code of doc.code ?? []) {
    if (!fs.existsSync(path.join(repoDir, code))) errors.push(`${doc.id}：相關程式路徑不存在 ${code}`);
  }
}
for (const id of onDisk) {
  if (!seen.has(id)) errors.push(`docs-site/docs/${id}.md 未登錄在 docs-site/catalog.mjs`);
}

// ---- 專案目錄樹（agent/project-tree.md）----
// 樹中每個路徑必須存在；下列範圍內每個受版控（或新增未忽略）的檔案都必須出現在樹中。
const treeFile = path.join(siteDir, 'agent', 'project-tree.md');
const coveredPrefixes = [
  'orchestrator/src/main/', 'orchestrator/teams/', 'orchestrator/prompts/', 'orchestrator/schemas/',
  'deploy/', 'docs-site/', '.agents/', '.claude/',
];
const excludedPrefixes = ['docs-site/docs/'];

const treeBlock = fs.readFileSync(treeFile, 'utf8').match(/## 目錄樹[\s\S]*?```text\r?\n([\s\S]*?)```/)?.[1];
const treeEntries = [];
if (!treeBlock) {
  errors.push('agent/project-tree.md 找不到「## 目錄樹」下的 text 程式碼區塊');
} else {
  const stack = [];
  for (const line of treeBlock.split(/\r?\n/).slice(1)) {
    const at = line.search(/[├└]── /);
    if (at < 0) continue;
    const depth = at / 4;
    const name = line.slice(at + 4).split(/\s+#/)[0].trim();
    const rel = [...stack.slice(0, depth), name].join('');
    treeEntries.push(rel);
    stack.length = depth;
    if (name.endsWith('/')) stack.push(name);
  }
}

const toRegex = (pattern) =>
  new RegExp(`^${pattern.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '[^/]*')}$`);
const repoFiles = execSync('git ls-files -co --exclude-standard', { cwd: repoDir, encoding: 'utf8' })
  .split(/\r?\n/).filter(Boolean).filter((f) => fs.existsSync(path.join(repoDir, f)));

for (const entry of treeEntries) {
  const clean = entry.replace(/\/$/, '');
  if (clean.includes('*')) {
    if (!repoFiles.some((f) => toRegex(clean).test(f))) errors.push(`目錄樹項目沒有對應檔案：${entry}`);
  } else if (!fs.existsSync(path.join(repoDir, clean))) {
    errors.push(`目錄樹列出的路徑不存在：${entry}`);
  }
}
const filePatterns = treeEntries.filter((e) => !e.endsWith('/')).map(toRegex);
const topLevel = new Set(treeEntries.map((e) => e.split('/')[0]));
for (const file of repoFiles) {
  if (!topLevel.has(file.split('/')[0])) errors.push(`目錄樹缺少頂層項目：${file.split('/')[0]}`);
  if (!coveredPrefixes.some((p) => file.startsWith(p)) || excludedPrefixes.some((p) => file.startsWith(p))) continue;
  if (!filePatterns.some((re) => re.test(file))) errors.push(`檔案未列在目錄樹（agent/project-tree.md）：${file}`);
}

const uniqueErrors = [...new Set(errors)];
if (uniqueErrors.length > 0) {
  console.error(`文件檢查失敗（${uniqueErrors.length} 項）：\n- ${uniqueErrors.join('\n- ')}`);
  process.exit(1);
}
console.log(`文件檢查通過：${docs.length} 份文件已登錄；目錄樹 ${treeEntries.length} 個項目與實際檔案一致。`);
