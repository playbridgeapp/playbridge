---
name: commit-and-open-pr
description: Safely stage task files, verify, commit, push, and create or update a PlayBridge pull request. Use when the user asks to commit, push, publish a branch, or open/update a PR without explicitly requesting a version bump or release.
---

# Commit and open PR

1. Inspect the branch, `git status`, and relevant diff. Preserve unrelated user changes.
2. Confirm the requested implementation is complete and run risk-appropriate focused tests.
3. Do not change versions or changelogs unless the user explicitly requests an uprev/release; use `release-and-publish` in that case.
4. Stage only files belonging to the task. Review the staged diff and run `git diff --check`.
5. Commit with a concise Conventional Commit message.
6. Follow the user's requested delivery target. For an explicitly requested direct push to `main`, review and verify the change, confirm the branch and remote, then push to `main`. Do not create a feature branch or PR in that workflow. Otherwise push the intended branch using configured authentication; do not infer a direct-main push from a generic push request.
7. Create or update a PR with `gh` when requested or when the task uses the repository's PR workflow. Describe the final behavior, tests, and material limitations. For multiline descriptions, use structured arguments or `--body-file` to preserve literal text.
8. Verify the remote commit and working-tree state. For a PR, verify its URL and initial checks. Report pending checks accurately; a successful push does not mean CI has passed.

Do not force-push, rewrite history, merge, or mutate release metadata unless the user explicitly authorizes that action.
