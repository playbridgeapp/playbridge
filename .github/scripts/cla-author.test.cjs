const test = require('node:test');
const assert = require('node:assert/strict');
const { prepare, verify, SIGNATURE, BRANCH, PATH } = require('./cla-author.cjs');

function fixture({ login = 'alice', commits = [], record = null, comments = [], failWrite = false } = {}) {
  const outputs = {}, statuses = [], writes = [], requests = [], failures = [];
  const pr = { number: 7, state: 'open', user: { login, id: 123 }, head: { sha: 'current-head' } };
  const context = { repo: { owner: 'playbridgeapp', repo: 'playbridge' }, runId: 9,
    payload: { pull_request: { number: 7 }, repository: { id: 42, default_branch: 'main' } } };
  const github = { rest: {
    pulls: { get: async () => ({ data: pr }), listCommits: async () => ({ data: commits }) },
    repos: {
      createCommitStatus: async value => statuses.push(value),
      getContent: async value => {
        assert.equal(value.path, PATH); assert.equal(value.ref, BRANCH);
        if (!record) throw Object.assign(new Error('missing'), { status: 404 });
        return { data: { encoding: 'base64', sha: 'file-sha', content: Buffer.from(JSON.stringify(record)).toString('base64') } };
      },
      createOrUpdateFileContents: async value => { if (failWrite) throw new Error('denied'); writes.push(value); },
    },
    issues: { listComments() {}, createComment: async value => requests.push(value) },
  }, paginate: async () => comments };
  const core = { setOutput: (key, value) => outputs[key] = value, setFailed: value => failures.push(value) };
  return { github, context, core, outputs, statuses, writes, requests, failures, pr };
}

const commit = (login, id) => ({ author: { login, id }, commit: { author: { name: login } } });
const signature = (id = 123) => ({ id: 55, user: { id, login: 'alice' }, body: SIGNATURE,
  created_at: '2026-10-14T00:00:00Z' });

test('only the PR author remains subject to the upstream check', async () => {
  const f = fixture({ commits: [commit('alice', 123), commit('bob', 456), commit('google-labs-jules[bot]', 789)] });
  await prepare(f);
  assert.equal(f.outputs['run-action'], 'true');
  assert.ok(f.outputs.allowlist.includes('bob'));
  assert.ok(f.outputs.allowlist.includes('google-labs-jules[bot]'));
  assert.ok(!f.outputs.allowlist.split(',').includes('alice'));
  assert.equal(f.statuses[0].sha, 'current-head');
  assert.equal(f.statuses[0].state, 'pending');
});

test('an unattributed PR author and unsafe CSV names bypass only the upstream action, not author enforcement', async () => {
  for (const commits of [[commit('bob', 456)], [commit('alice', 123), { commit: { author: { name: '*,alice' } } }]]) {
    const f = fixture({ commits });
    await prepare(f);
    assert.equal(f.outputs.run, 'true');
    assert.equal(f.outputs['run-action'], 'false');
    await verify(f);
    assert.equal(f.statuses.at(-1).state, 'failure');
    assert.equal(f.requests.length, 1);
  }
});

test('owner and all configured bots are exempt without a signature file', async () => {
  for (const login of ['playbridgeapp', 'dependabot[bot]', 'github-actions[bot]', 'google-labs-jules[bot]']) {
    const f = fixture({ login });
    await prepare(f);
    assert.equal(f.outputs.run, 'false');
    assert.equal(f.statuses.at(-1).state, 'success');
    assert.equal(f.writes.length, 0);
  }
});

test('a stored authenticated author signature passes on the latest PR head', async () => {
  const f = fixture({ record: { signedContributors: [{ id: 123, name: 'alice' }] } });
  await verify(f);
  assert.equal(f.statuses.at(-1).state, 'success');
  assert.equal(f.writes.length, 0);
  assert.equal(f.failures.length, 0);
});

test('another user, a co-author trailer and a partial signing phrase cannot sign for the PR author', async () => {
  for (const comment of [signature(456), { ...signature(), body: 'Co-authored-by: Alice\n' + SIGNATURE },
    { ...signature(), body: 'I hereby sign' }]) {
    const f = fixture({ comments: [comment] });
    await verify(f);
    assert.equal(f.statuses.at(-1).state, 'failure');
    assert.equal(f.writes.length, 0);
    assert.equal(f.failures.length, 1);
  }
});

test('the PR author can sign even without an attributed commit, using the upstream storage schema', async () => {
  const f = fixture({ comments: [signature()] });
  await verify(f);
  const write = f.writes[0];
  assert.equal(write.path, PATH); assert.equal(write.branch, BRANCH);
  const data = JSON.parse(Buffer.from(write.content, 'base64').toString());
  assert.equal(data.signedContributors.length, 1);
  assert.deepEqual(data.signedContributors[0], { name: 'alice', id: 123, comment_id: 55,
    created_at: '2026-10-14T00:00:00Z', repoId: 42, pullRequestNo: 7 });
  assert.equal(f.statuses.at(-1).state, 'success');
});

test('updates preserve earlier signatures and use the file SHA for conditional writes', async () => {
  const f = fixture({ record: { signedContributors: [{ id: 456, name: 'bob' }] }, comments: [signature()] });
  await verify(f);
  assert.equal(f.writes[0].sha, 'file-sha');
  const data = JSON.parse(Buffer.from(f.writes[0].content, 'base64').toString());
  assert.equal(data.signedContributors.length, 2);
});

test('malformed records or failed persistence fail closed', async () => {
  for (const options of [{ record: { wrong: [] } }, { comments: [signature()], failWrite: true }]) {
    const f = fixture(options);
    await verify(f);
    assert.equal(f.statuses.at(-1).state, 'failure');
    assert.equal(f.failures.length, 1);
  }
});

test('existing CLA request comments are not duplicated', async () => {
  const f = fixture({ comments: [{ user: { login: 'github-actions[bot]' }, body: '<!-- playbridge-cla-author-v1 -->' }] });
  await verify(f);
  assert.equal(f.requests.length, 0);
  assert.equal(f.statuses.at(-1).state, 'failure');
});

test('issue-comment events resolve their pull request and closed PRs do not write signatures', async () => {
  const f = fixture({ comments: [signature()] });
  delete f.context.payload.pull_request;
  f.context.payload.issue = { number: 7, pull_request: {} };
  await prepare(f);
  assert.equal(f.outputs.run, 'true');
  f.pr.state = 'closed';
  await verify(f);
  assert.equal(f.writes.length, 0);
});
