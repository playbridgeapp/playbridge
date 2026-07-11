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
6. Push the current feature branch using the environment's configured authentication.
7. Create or update the PR with `gh`. Summarize behavior changes, tests, risks, and any manual verification still needed.
8. Verify the PR URL and initial checks, then report the commit and PR state.

Do not force-push, rewrite history, merge, or mutate release metadata unless the user explicitly authorizes that action.
