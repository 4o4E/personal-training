import { readFile, mkdir, access, rename, rm, readdir } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// 素材地址由已整理的目录提供；失败就终止，不用其他图片代替动作教学。
const root = fileURLToPath(new URL('../', import.meta.url));
const documents = JSON.parse(await readFile(path.join(root, 'catalog/catalog.json'), 'utf8'));
const media = [...new Map(documents.filter(d => d.kind === 'media').map(d => [d.data.fileName, d.data])).values()];
const target = path.join(root, 'catalog/media');
await mkdir(target, { recursive: true });
const download = promisify(execFile);
const proxyIndex = process.argv.indexOf('--proxy');
const proxyArguments = proxyIndex < 0 ? [] : ['--proxy', process.argv[proxyIndex + 1]];
let next = 0;
await Promise.all(Array.from({ length: 6 }, async () => {
  while (next < media.length) {
    const item = media[next++];
    const file = path.join(target, item.fileName);
    if (!process.argv.includes('--refresh')) {
      try { await access(file); continue; } catch (error) { if (error.code !== 'ENOENT') throw error; }
    }
    const temporary = `${file}.part`;
    try {
      await download(process.platform === 'win32' ? 'curl.exe' : 'curl', [
        '--fail', '--location', '--silent', '--show-error', '--connect-timeout', '30',
        ...proxyArguments, '--output', temporary, item.sourceUrl,
      ]);
      await rename(temporary, file);
    } finally { await rm(temporary, { force: true }); }
  }
}));
if (process.argv.includes('--prune')) {
  const retained = new Set(media.map(item => item.fileName));
  for (const name of await readdir(target)) {
    if (!retained.has(name)) await rm(path.join(target, name));
  }
}
console.log(`已准备 ${media.length} 个实际教学素材文件。`);
