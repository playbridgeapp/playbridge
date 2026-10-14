// Runs only from the trusted default branch; never loads pull-request code.
const BRANCH = 'cla-signatures';
const PATH = 'signatures/cla-v1.json';
const STATUS = 'CLA / author';
const SIGNATURE = 'I have read the CLA Document and I hereby sign the CLA';
const EXEMPT = ['playbridgeapp', 'dependabot[bot]', 'github-actions[bot]', 'google-labs-jules[bot]'];

async function subject(github, context) {
  const number = context.payload.pull_request?.number ?? context.payload.issue?.number;
  if (!number) throw new Error('Missing pull request');
  return (await github.rest.pulls.get({ ...context.repo, pull_number: number })).data;
}

async function status(github, context, pr, state, description) {
  await github.rest.repos.createCommitStatus({
    ...context.repo, sha: pr.head.sha, state, context: STATUS, description,
    target_url: `${context.serverUrl || 'https://github.com'}/${context.repo.owner}/${context.repo.repo}/actions/runs/${context.runId}`,
  });
}

function exempt(pr) {
  return EXEMPT.includes(pr.user.login.toLowerCase());
}

async function prepare({ github, context, core }) {
  const pr = await subject(github, context);
  if (pr.state !== 'open') { core.setOutput('run', 'false'); return; }
  if (exempt(pr)) {
    await status(github, context, pr, 'success', 'PR author is exempt from the CLA');
    core.setOutput('run', 'false');
    return;
  }
  core.setOutput('run', 'true');
  await status(github, context, pr, 'pending', 'Checking the PR author against CLA version 1');
  // v2.6.1 examines the first 100 commit authors, not PR authors or co-author trailers.
  // Exempt everyone except the PR author. The final guard also covers PR authors
  // with no attributed commit, or names that cannot safely fit the action's CSV input.
  const { data: commits } = await github.rest.pulls.listCommits({ ...context.repo, pull_number: pr.number, per_page: 100 });
  const names = new Set(EXEMPT);
  let authorPresent = false, safe = true;
  for (const commit of commits) {
    const user = commit.author || commit.committer;
    const name = user?.login || commit.commit?.author?.name || commit.commit?.committer?.name;
    if (user?.id === pr.user.id) { authorPresent = true; continue; }
    if (!name || /[,\r\n*]/.test(name) || name.trim() !== name || name.toLowerCase() === pr.user.login.toLowerCase()) {
      safe = false;
    } else names.add(name);
  }
  core.setOutput('allowlist', [...names].join(','));
  core.setOutput('run-action', String(authorPresent && safe));
}

async function verify({ github, context, core }) {
  let pr;
  try {
    pr = await subject(github, context);
    if (pr.state !== 'open') return;
    if (exempt(pr)) { await status(github, context, pr, 'success', 'PR author is exempt from the CLA'); return; }
    let record = { signedContributors: [] }, sha;
    try {
      const result = await github.rest.repos.getContent({ ...context.repo, path: PATH, ref: BRANCH });
      if (Array.isArray(result.data) || result.data.encoding !== 'base64') throw new Error('Invalid signature file');
      record = JSON.parse(Buffer.from(result.data.content, 'base64').toString('utf8'));
      sha = result.data.sha;
      if (!Array.isArray(record.signedContributors)) throw new Error('Invalid signature records');
    } catch (error) { if (error.status !== 404) throw error; }
    let signed = record.signedContributors.some(entry => entry.id === pr.user.id);
    if (!signed) {
      const comments = await github.paginate(github.rest.issues.listComments, { ...context.repo, issue_number: pr.number, per_page: 100 });
      const signature = comments.find(comment => comment.user?.id === pr.user.id &&
        comment.body?.trim().toLowerCase() === SIGNATURE.toLowerCase());
      if (signature) {
        // Same schema as the pinned action, including the authenticated GitHub ID.
        record.signedContributors.push({ name: pr.user.login, id: pr.user.id,
          comment_id: signature.id, created_at: signature.created_at,
          repoId: context.payload.repository.id, pullRequestNo: pr.number });
        await github.rest.repos.createOrUpdateFileContents({ ...context.repo, path: PATH, branch: BRANCH,
          ...(sha ? { sha } : {}), message: `Record CLA v1 signature for @${pr.user.login}`,
          content: Buffer.from(JSON.stringify(record, null, 2) + '\n').toString('base64') });
        signed = true;
      } else {
        const alreadyRequested = comments.some(comment => comment.user?.login === 'github-actions[bot]' &&
          (comment.body?.includes('<!-- playbridge-cla-author-v1 -->') ||
           (comment.body?.includes('CLA Assistant Lite') && comment.body?.includes(`@${pr.user.login}`))));
        if (!alreadyRequested) {
          const document = `${context.serverUrl || 'https://github.com'}/${context.repo.owner}/${context.repo.repo}/blob/${context.payload.repository.default_branch}/CLA.md`;
          await github.rest.issues.createComment({ ...context.repo, issue_number: pr.number,
            body: `<!-- playbridge-cla-author-v1 -->\n@${pr.user.login}, please read [CLA version 1](${document}) and post this exact comment to sign:\n\n> ${SIGNATURE}\n\nOnly the PR author signs; commit co-author attribution does not require additional signatures.` });
        }
      }
    }
    await status(github, context, pr, signed ? 'success' : 'failure',
      signed ? 'PR author has signed CLA version 1' : 'PR author must sign CLA version 1');
    if (!signed) core.setFailed('The PR author has not signed CLA version 1.');
  } catch (error) {
    if (pr) await status(github, context, pr, 'failure', 'Could not verify or persist the PR author signature').catch(() => {});
    core.setFailed('CLA verification failed. Check the signature branch, file format and workflow permissions.');
  }
}

module.exports = { prepare, verify, BRANCH, PATH, STATUS, SIGNATURE };
