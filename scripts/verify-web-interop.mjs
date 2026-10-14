// Run the Android unit tests first. No production credentials or keys are used.
import fs from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
const root = path.resolve(import.meta.dirname, '..');
const source = path.resolve(process.argv[2] ?? path.join(root, '../pax-console-workos'));
const { decryptEnvelope } = await import(pathToFileURL(path.join(source, 'src/features/e2ee/envelope.ts')));
const envelope = JSON.parse(await fs.readFile(path.join(root, 'android/app/build/test-vectors/android-envelope.json'), 'utf8'));
const plaintext = new TextDecoder().decode(await decryptEnvelope(Uint8Array.from({length:32}, (_,i) => i), 'command', envelope));
if (plaintext !== 'Android -> Web 世界') throw new Error('Android/Web compatibility failed');
console.log('PASS: upstream WebCrypto decrypted the Android envelope');
