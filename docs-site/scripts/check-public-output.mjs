import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const site = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const out = path.join(site, 'build-public');
const rules = [
  ['private-key', /-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----/],
  ['provider-token', /(?:xox[baprs]-[A-Za-z0-9-]{16,}|xapp-[A-Za-z0-9-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-(?:ant-|proj-)[A-Za-z0-9_-]{16,}|AKIA[A-Z0-9]{16})/],
  ['credential-assignment', /(?:password|passwd|client_secret|api_key|access_token)\s*["']?\s*[:=]\s*["'][^"'\s]{8,}["']/i],
  ['private-address', /\b(?:127\.0\.0\.1|192\.168\.\d+\.\d+|10\.\d+\.\d+\.\d+|172\.(?:1[6-9]|2\d|3[01])\.\d+\.\d+)\b/],
  ['private-path', /(?:[CD]:[\\/](?:Users|agent_team)[\\/]|\/home\/agent\/|\/opt\/ai-team|\.ai\/(?:context|progress|decisions)\.md)/i],
];
const forbidden = /(?:^|\/)(?:agent|docs|catalog|\.git|\.ai)(?:\/|$)|(?:^|\/)(?:llms(?:-full)?\.txt|env\.sh|.*\.(?:pem|key|map))$/;
const failures = [];
function scan(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name);
    const rel = path.relative(out, file).split(path.sep).join('/');
    if (entry.isSymbolicLink()) { failures.push(`${rel}: symbolic-link`); continue; }
    if (forbidden.test(rel)) failures.push(`${rel}: internal-output`);
    if (entry.isDirectory()) { scan(file); continue; }
    if (!/\.(?:html|js|css|json|txt|xml|md|svg)$/.test(rel)) continue;
    const text = fs.readFileSync(file, 'utf8');
    for (const [name, pattern] of rules) if (pattern.test(text)) failures.push(`${rel}: ${name}`);
  }
}
if (!fs.existsSync(path.join(out, 'index.html'))) throw new Error('Missing public build');
scan(out);
if (failures.length) {
  // 僅印路徑與規則名，避免掃描時將疑似秘密本身寫入 log。
  console.error(failures.join('\n'));
  process.exit(1);
}
console.log('公開產物檢查通過：無內部頁面、私人路徑、私網位址或已知憑證樣式。');
