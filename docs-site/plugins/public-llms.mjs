import fs from 'node:fs/promises';
import path from 'node:path';
import {manual} from '../public-manual.mjs';

// 純公開來源；發布檢查逐位元核對輸出與來源。
export async function publicOutputs(siteDir, config) {
  const base = `${config.url.replace(/\/$/, '')}${config.baseUrl}`;
  const outputs = {};
  const index = ['# AI Team 公開手冊', '', '> 使用、管理、架構與 AI 閱讀索引；不含私人部署與交接紀錄。', '',
    '建議先讀 AI 閱讀入口；實際行為以程式與任務快照為準。測試、部署與端對端證據須分開判斷。', ''];
  const full = ['# AI Team 公開手冊全文', ''];
  for (const group of new Set(manual.map(p => p.group))) {
    index.push(`## ${group}`, '');
    for (const page of manual.filter(p => p.group === group)) {
      const raw = await fs.readFile(path.join(siteDir, 'public-docs', `${page.id}.md`), 'utf8');
      outputs[`manual/${page.id}.md`] = raw;
      index.push(`- [${page.title}](${base}manual/${page.id}.md): ${page.summary} 網頁：${base}${page.route}/`);
      full.push('---', '', `<!-- source: public-docs/${page.id}.md -->`, '', raw.replace(/^\uFEFF?---\r?\n[\s\S]*?\r?\n---\r?\n/, '').trim(), '');
    }
    index.push('');
  }
  outputs['llms.txt'] = index.join('\n') + '\n';
  outputs['llms-full.txt'] = full.join('\n');
  return outputs;
}
export default function publicLlms(context) {
  return {name: 'public-llms', async postBuild({outDir, siteConfig}) {
    for (const [file, body] of Object.entries(await publicOutputs(context.siteDir, siteConfig))) {
      const target = path.join(outDir, file);
      await fs.mkdir(path.dirname(target), {recursive: true});
      await fs.writeFile(target, body);
    }
  }};
}
