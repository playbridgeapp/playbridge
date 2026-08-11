import { copyFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const siteRoot = path.resolve(scriptDirectory, '..');
const repositoryRoot = path.resolve(siteRoot, '..', '..');

export const CLI_INSTALLERS = ['install.sh', 'install.ps1'];

export async function publishCliInstallers({
  cliDirectory = path.join(repositoryRoot, 'cli'),
  outputDirectory = path.join(siteRoot, 'build')
} = {}) {
  await mkdir(outputDirectory, { recursive: true });
  await Promise.all(
    CLI_INSTALLERS.map((name) =>
      copyFile(path.join(cliDirectory, name), path.join(outputDirectory, name))
    )
  );
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await publishCliInstallers();
}
