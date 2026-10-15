// PAX_IOS_INTEROP_OUTPUT=/tmp/pax-ios-envelope.json swift test ...
import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
const root = path.resolve(import.meta.dirname, '..');
const source = path.resolve(process.argv[3] ?? path.join(root, '../pax-console-workos'));
const { decryptEnvelope } = await import(pathToFileURL(path.join(source, 'src/features/e2ee/envelope.ts')));
const fixture = JSON.parse(await fs.readFile(process.argv[2] ?? '/tmp/pax-ios-envelope.json', 'utf8'));
const plaintext = new TextDecoder().decode(await decryptEnvelope(Buffer.from(fixture.root, 'base64'), 'command', fixture.envelope));
if (plaintext !== fixture.plaintext) throw new Error('iOS/Web compatibility failed');
console.log('PASS: upstream WebCrypto decrypted the iOS CryptoKit envelope');
