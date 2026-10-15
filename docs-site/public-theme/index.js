import {fileURLToPath} from 'node:url';
export default function publicTheme() {
  return { name: 'ai-team-public-theme', getThemePath: () => fileURLToPath(new URL('./theme', import.meta.url)) };
}
