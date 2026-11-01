import { readFile, mkdir, copyFile, writeFile, access } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';

// 版本号读取实际 APK 构建元数据，避免发布说明与安装包内部版本分离。
const version = process.argv[2];
const metadata = JSON.parse(await readFile('android/build/outputs/apk/release/output-metadata.json', 'utf8'));
const apk = metadata.elements[0];
assert.equal(apk.versionName, version);
const [major, minor, patch] = version.split('.').map(Number);
assert.equal(apk.versionCode, major * 1_000_000 + minor * 1_000 + patch + 1);
const apkName = `personal-training-${version}.apk`;
const serverName = `personal-training-server-${version}.zip`;
const collectorName = `personal-training-collector-${version}.zip`;
await access(`release/${collectorName}`);
const bytes = await readFile(`android/build/outputs/apk/release/${apk.outputFile}`);
const changelog = await readFile('CHANGELOG.md', 'utf8');
const notes = changelog.split(`## ${version}\n`)[1]?.split('\n## ')[0].trim();
assert.ok(notes, 'CHANGELOG.md 缺少该版本的更新说明');
await mkdir('release', { recursive: true });
await writeFile(`release/${apkName}`, bytes);
await copyFile(`server/build/distributions/server-${version}.zip`, `release/${serverName}`);
await writeFile('release/update.json', JSON.stringify({ version, versionCode: apk.versionCode, apk: apkName, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'), notes }, null, 2) + '\n');
await writeFile('release/notes.md', notes + '\n');
console.log(`已准备 ${apkName}、${serverName}、${collectorName} 和 update.json`);
