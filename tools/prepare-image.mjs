import { mkdir, readdir, copyFile } from 'node:fs/promises';
import { join } from 'node:path';
import assert from 'node:assert/strict';

// 仅移动 Release 运行包中的原始 JAR，不编译或改写；素材独立层可被后续版本复用。
const [source, destination] = process.argv.slice(2);
const files = (await readdir(source)).filter(name => name.endsWith('.jar'));
assert.ok(files.some(name => name.startsWith('server-')), '缺少后端 JAR');
assert.ok(files.some(name => name.startsWith('training-catalog-')), '缺少动作素材 JAR');
for (const group of ['dependencies', 'catalog', 'application']) await mkdir(join(destination, group), { recursive: true });
for (const name of files) {
  const group = /^(server|shared)-/.test(name) ? 'application' : name.startsWith('training-catalog-') ? 'catalog' : 'dependencies';
  await copyFile(join(source, name), join(destination, group, name));
}
console.log(`已从 Release 整理 ${files.length} 份原始运行文件`);
