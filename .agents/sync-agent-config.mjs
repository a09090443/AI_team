#!/usr/bin/env node
// 各 AI 工具設定的單一來源同步腳本。
//
//   規則   AGENTS.md（手寫，本腳本不產生；只檢查各工具的入口設定仍指向它）
//   Skills .agents/skills/<名稱>/SKILL.md  → 鏡像到 .claude/skills/（Claude Code 只讀這裡）
//   MCP    .agents/mcp.json                → .mcp.json、.cursor/mcp.json、.vscode/mcp.json、
//                                            .gemini/settings.json、opencode.json、
//                                            .codex/config.toml（本機檔，只改標記區塊）
//
// 用法：
//   node .agents/sync-agent-config.mjs          寫入所有產生檔
//   node .agents/sync-agent-config.mjs --check  只檢查，不一致時結束碼 1（docs-site build 前自動執行）
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const check = process.argv.includes('--check');
const problems = [];
const written = [];

const rel = (p) => path.relative(repo, p).split(path.sep).join('/');
const read = (p) => (fs.existsSync(p) ? fs.readFileSync(p, 'utf8').replace(/\r\n/g, '\n') : null);
const json = (value) => JSON.stringify(value, null, 2) + '\n';
const GENERATED = '由 .agents/sync-agent-config.mjs 從 .agents/mcp.json 產生，請勿手改';

function output(file, content) {
  const current = read(file);
  if (current === content) return;
  if (check) {
    problems.push(`${rel(file)} 與單一來源不一致，請執行 node .agents/sync-agent-config.mjs`);
    return;
  }
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, content);
  written.push(rel(file));
}

// ---- MCP ----
const source = JSON.parse(read(path.join(repo, '.agents/mcp.json')) ?? '{"mcpServers":{}}');
const servers = Object.entries(source.mcpServers ?? {});
for (const [name, s] of servers) {
  if (!/^[A-Za-z0-9_-]+$/.test(name)) problems.push(`MCP 名稱只能用英數、- 與 _：${name}`);
  if (!s.command === !s.url) problems.push(`MCP ${name} 必須剛好設定 command（stdio）或 url（http）其中一個`);
}
const pick = (entries, map) => Object.fromEntries(entries.map(([name, s]) => [name, map(s)]));
const clean = (o) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined));
const stdio = (s) => clean({ command: s.command, args: s.args ?? [], env: s.env });

output(path.join(repo, '.mcp.json'), json({
  mcpServers: pick(servers, (s) => (s.url ? clean({ type: 'http', url: s.url, headers: s.headers }) : stdio(s))),
}));
output(path.join(repo, '.cursor/mcp.json'), json({
  mcpServers: pick(servers, (s) => (s.url ? clean({ url: s.url, headers: s.headers }) : stdio(s))),
}));
output(path.join(repo, '.vscode/mcp.json'), json({
  servers: pick(servers, (s) => (s.url
    ? clean({ type: 'http', url: s.url, headers: s.headers })
    : clean({ type: 'stdio', ...stdio(s) }))),
}));
output(path.join(repo, '.gemini/settings.json'), json({
  context: { fileName: ['AGENTS.md'] },
  mcpServers: pick(servers, (s) => (s.url ? clean({ httpUrl: s.url, headers: s.headers }) : stdio(s))),
}));
output(path.join(repo, 'opencode.json'), json({
  $schema: 'https://opencode.ai/config.json',
  mcp: pick(servers, (s) => (s.url
    ? clean({ type: 'remote', url: s.url, headers: s.headers, enabled: true })
    : clean({ type: 'local', command: [s.command, ...(s.args ?? [])], environment: s.env, enabled: true }))),
}));

// Codex：.codex/ 是本機檔（gitignore），只替換標記區塊，保留使用者自己的其他設定。
const BEGIN = '# >>> agents-sync：' + GENERATED + ' >>>';
const END = '# <<< agents-sync <<<';
const toml = (v) => (Array.isArray(v) ? `[${v.map(toml).join(', ')}]` : JSON.stringify(v));
const codexBlock = [BEGIN, ...servers.flatMap(([name, s]) => [
  `[mcp_servers.${name}]`,
  ...(s.url
    ? [`url = ${toml(s.url)}`, ...(s.headers ? [`http_headers = { ${Object.entries(s.headers).map(([k, v]) => `${JSON.stringify(k)} = ${toml(v)}`).join(', ')} }`] : [])]
    : [`command = ${toml(s.command)}`, `args = ${toml(s.args ?? [])}`]),
  ...(s.env ? [`env = { ${Object.entries(s.env).map(([k, v]) => `${k} = ${toml(v)}`).join(', ')} }`] : []),
  '',
]), END].join('\n');
if (!check) {
  const codexFile = path.join(repo, '.codex/config.toml');
  const current = read(codexFile) ?? '';
  const outside = current.replace(new RegExp(`${BEGIN.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}[\\s\\S]*?${END}\\n?`), '');
  const clash = servers.map(([n]) => n).filter((n) => new RegExp(`^\\[mcp_servers\\.${n}\\]`, 'm').test(outside));
  if (clash.length > 0) {
    problems.push(`.codex/config.toml 已在標記區塊外自行定義 ${clash.join('、')}，未寫入 Codex，請先移除重複定義`);
  } else if (servers.length > 0 || current.includes(BEGIN)) {
    output(codexFile, `${outside.trimEnd()}${outside.trim() ? '\n\n' : ''}${codexBlock}\n`);
  }
}

// ---- Skills ----
const skillSource = path.join(repo, '.agents/skills');
const skillMirror = path.join(repo, '.claude/skills');
const listFiles = (dir) => (fs.existsSync(dir)
  ? fs.readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter((e) => e.isFile())
    .map((e) => path.relative(dir, path.join(e.parentPath ?? e.path, e.name)).split(path.sep).join('/'))
  : []);
const sourceSkills = fs.existsSync(skillSource)
  ? fs.readdirSync(skillSource, { withFileTypes: true }).filter((e) => e.isDirectory()).map((e) => e.name) : [];
for (const skill of sourceSkills) {
  const skillMd = read(path.join(skillSource, skill, 'SKILL.md'));
  const front = skillMd?.match(/^---\n([\s\S]*?)\n---/)?.[1] ?? '';
  if (!skillMd) problems.push(`.agents/skills/${skill}/ 缺少 SKILL.md`);
  else if (front.match(/^name:\s*(.+)$/m)?.[1].trim() !== skill) problems.push(`.agents/skills/${skill}/SKILL.md 的 name 必須等於目錄名稱`);
  else if (!/^description:\s*\S/m.test(front)) problems.push(`.agents/skills/${skill}/SKILL.md 缺少 description`);
}
for (const file of listFiles(skillSource)) {
  output(path.join(skillMirror, file), read(path.join(skillSource, file)));
}
for (const file of listFiles(skillMirror)) {
  if (!fs.existsSync(path.join(skillSource, file))) {
    problems.push(`.claude/skills/${file} 不在單一來源；skill 請放在 .agents/skills/ 再同步（本腳本不會自動刪除）`);
  }
}

// ---- 規則入口 ----
if (read(path.join(repo, 'CLAUDE.md'))?.trim() !== '@AGENTS.md') {
  problems.push('CLAUDE.md 只能包含 @AGENTS.md 一行；規則請寫在 AGENTS.md');
}
const vscode = JSON.parse(read(path.join(repo, '.vscode/settings.json')) ?? '{}');
if (vscode['chat.useAgentsMdFile'] !== true) problems.push('.vscode/settings.json 需設定 "chat.useAgentsMdFile": true');

if (problems.length > 0) {
  console.error(`AI 工具設定檢查失敗（${problems.length} 項）：\n- ${problems.join('\n- ')}`);
  process.exit(1);
}
console.log(check
  ? `AI 工具設定一致：${servers.length} 個 MCP server、${sourceSkills.length} 個 skill。`
  : `已同步：${written.length > 0 ? written.join('、') : '無變更'}`);
