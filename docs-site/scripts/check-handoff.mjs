// .ai 交接檔保存規則檢查，build 前自動執行，失敗時結束碼 1（規則見 AGENTS.md「跨工具交接」）：
// 1. context.md 不超過 CONTEXT_MAX_LINES 行（覆寫成目前狀態，無法自動修正）。
// 2. progress.md 不超過 PROGRESS_MAX_LINES 行；--fix 依日期由新到舊排序，並把最舊日期的條目整天移到
//    .ai/archive/progress-YYYY-MM.md，直到低於上限。
// 3. decisions.md 開頭索引與條目標題一致；--fix 重產索引。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const CONTEXT_MAX_LINES = 60;
const PROGRESS_MAX_LINES = 300;
const INDEX_START = '<!-- 索引開始：由 docs-site/scripts/check-handoff.mjs --fix 產生 -->';
const INDEX_END = '<!-- 索引結束 -->';

const repoDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const aiDir = path.join(repoDir, '.ai');
const archiveDir = path.join(aiDir, 'archive');
const fix = process.argv.includes('--fix');

const read = (file) => fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n');
const lineCount = (text) => text.replace(/\n+$/, '').split('\n').length;
const dateOf = (heading) => (heading.match(/\d{4}-\d{2}-\d{2}/) ?? ['0000-00-00'])[0];

// 以「## 」切出條目；前言（標題與說明）保留在 preamble。
const splitEntries = (text) => {
  const preamble = [];
  const entries = [];
  for (const line of text.replace(/\n+$/, '').split('\n')) {
    if (line.startsWith('## ')) entries.push({ date: dateOf(line), lines: [line] });
    else if (entries.length) entries.at(-1).lines.push(line);
    else preamble.push(line);
  }
  for (const entry of entries) {
    while (entry.lines.length > 1 && entry.lines.at(-1).trim() === '') entry.lines.pop();
  }
  return { preamble, entries };
};

// 依日期由新到舊；同日保持原順序。
const sortEntries = (entries) =>
  entries.map((entry, i) => ({ entry, i }))
    .sort((a, b) => b.entry.date.localeCompare(a.entry.date) || a.i - b.i)
    .map(({ entry }) => entry);

const render = (preamble, entries) => {
  const head = preamble.join('\n').replace(/\n+$/, '');
  return `${head}\n\n${entries.map((entry) => entry.lines.join('\n')).join('\n\n')}\n`;
};

const errors = [];

// 1. context.md
const contextFile = path.join(aiDir, 'context.md');
const contextLines = lineCount(read(contextFile));
if (contextLines > CONTEXT_MAX_LINES) {
  errors.push(`.ai/context.md 有 ${contextLines} 行，超過 ${CONTEXT_MAX_LINES} 行：請改寫成目前狀態，已完成的移到 progress.md`);
}

// 2. progress.md
const progressFile = path.join(aiDir, 'progress.md');
const progressText = read(progressFile);
if (lineCount(progressText) > PROGRESS_MAX_LINES) {
  if (!fix) {
    errors.push(`.ai/progress.md 超過 ${PROGRESS_MAX_LINES} 行：執行 node docs-site/scripts/check-handoff.mjs --fix 歸檔最舊的條目`);
  } else {
    const { preamble, entries } = splitEntries(progressText);
    const kept = sortEntries(entries);
    const moved = [];
    while (kept.length > 1 && lineCount(render(preamble, kept)) > PROGRESS_MAX_LINES) {
      const oldest = kept.at(-1).date;
      while (kept.length > 1 && kept.at(-1).date === oldest) moved.push(kept.pop());
    }
    if (lineCount(render(preamble, kept)) > PROGRESS_MAX_LINES) {
      errors.push(`.ai/progress.md 只剩同一天的條目仍超過 ${PROGRESS_MAX_LINES} 行：請精簡條目內容`);
    }
    const byMonth = Map.groupBy(moved, (entry) => entry.date.slice(0, 7));
    fs.mkdirSync(archiveDir, { recursive: true });
    for (const [month, monthEntries] of byMonth) {
      const archiveFile = path.join(archiveDir, `progress-${month}.md`);
      const existing = fs.existsSync(archiveFile)
        ? splitEntries(read(archiveFile))
        : { preamble: [`# 工作進度歸檔 ${month}`, '', '> 由 progress.md 移出的舊條目，只供查詢，開場不讀。'], entries: [] };
      fs.writeFileSync(archiveFile, render(existing.preamble, sortEntries([...existing.entries, ...monthEntries])));
      console.log(`已歸檔 ${monthEntries.length} 則條目到 .ai/archive/progress-${month}.md`);
    }
    fs.writeFileSync(progressFile, render(preamble, kept));
  }
}

// 3. decisions.md 索引
const decisionsFile = path.join(aiDir, 'decisions.md');
const decisionsText = read(decisionsFile);
const headings = decisionsText.split('\n')
  .filter((line) => /^#{1,3} \d{4}-\d{2}-\d{2}/.test(line))
  .map((line) => line.replace(/^#+ /, ''));
const index = [INDEX_START, ...sortEntries(headings.map((h) => ({ date: dateOf(h), lines: [h] })))
  .map((entry) => `- ${entry.lines[0]}`), INDEX_END].join('\n');
const start = decisionsText.indexOf(INDEX_START);
const end = decisionsText.indexOf(INDEX_END);
const current = start >= 0 && end > start ? decisionsText.slice(start, end + INDEX_END.length) : null;
if (current !== index) {
  if (!fix) {
    errors.push('.ai/decisions.md 開頭索引與條目不一致：執行 node docs-site/scripts/check-handoff.mjs --fix 重產索引');
  } else if (current !== null) {
    fs.writeFileSync(decisionsFile, decisionsText.replace(current, index));
  } else {
    // 索引放在檔案標題與說明之後、第一則條目之前。
    const lines = decisionsText.split('\n');
    const first = lines.findIndex((line, i) => i > 0 && /^#{1,3} /.test(line));
    lines.splice(first, 0, '## 索引', '', index, '');
    fs.writeFileSync(decisionsFile, lines.join('\n'));
  }
}

if (errors.length) {
  console.error(`交接檔檢查失敗（${errors.length} 項）：\n- ${errors.join('\n- ')}`);
  process.exit(1);
}
console.log('交接檔檢查通過');
