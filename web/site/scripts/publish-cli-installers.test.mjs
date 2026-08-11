import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { CLI_INSTALLERS, publishCliInstallers } from './publish-cli-installers.mjs';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const cliDirectory = path.resolve(scriptDirectory, '..', '..', '..', 'cli');

test('publishes canonical CLI installers byte-for-byte', async () => {
  const outputDirectory = await mkdtemp(path.join(os.tmpdir(), 'playbridge-installers-'));
  try {
    await publishCliInstallers({ cliDirectory, outputDirectory });
    for (const name of CLI_INSTALLERS) {
      const canonical = await readFile(path.join(cliDirectory, name));
      const published = await readFile(path.join(outputDirectory, name));
      assert.deepEqual(published, canonical, `${name} must not drift from its canonical source`);
    }
  } finally {
    await rm(outputDirectory, { recursive: true, force: true });
  }
});
