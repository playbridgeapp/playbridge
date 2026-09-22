---
name: code-review-graph-workflow
description: Use an available code-review graph for high-impact or repository-wide exploration, debugging, change review, impact analysis, test discovery, and scoped refactoring. Use Serena first for localized symbol navigation and references; invoke this workflow when structural relationships, execution flows, or blast radius matter.
---

# Code-review graph workflow

Use Serena first for localized symbol navigation, references, implementations, and
diagnostics. Invoke this workflow when the task crosses project boundaries, changes a
protocol or ABI, needs execution-flow or architecture analysis, reviews regressions,
or has meaningful blast-radius risk.

Start with `get_minimal_context(task="...")`, then select only the graph tools needed.

If the code-review graph tools are unavailable, continue with repository-native search, focused file inspection, version-control history, and relevant tests.

## Explore

- Use `get_architecture_overview` or `list_communities` for broad structure.
- Use `semantic_search_nodes` to locate code and `query_graph` for callers, callees, imports, and children.
- Use `list_flows`/`get_flow` for execution paths.

## Debug

- Locate the failing behavior with `semantic_search_nodes`.
- Trace callers/callees or flows, then compare recent changes with `detect_changes` when regression history matters.
- Use `get_impact_radius` before changing a suspected high-impact entity.
- Confirm hypotheses with focused source reads, logs, and tests; graph relationships are evidence, not proof of runtime behavior.

## Review

- Use `detect_changes` against the correct base, followed by `get_affected_flows` or `get_impact_radius` for risky changes.
- Query `tests_for` on high-risk entities and inspect exact changed lines before reporting a finding.
- Identify changed behavior without coverage and suggest concrete tests for it.
- Report actionable defects first with file/line evidence. Do not inflate generic coverage gaps into defects.

## Refactor

- Analyze the requested symbol/files with impact and relationship queries before editing.
- Use rename preview/apply only for a requested rename and review the generated diff.
- Use dead-code or broad suggestion modes only when the user explicitly requests an audit; do not expand a scoped refactor.
- Re-run change detection and focused tests after edits.

## Token and complexity policy

- Do not call the graph mechanically for every task.
- Prefer Serena plus focused reads for a single file, symbol, bug, or localized refactor.
- Add graph context only when its repository-wide relationships or flow analysis will
  change the implementation or verification decision.

Use minimal detail initially, but increase detail and tool calls whenever necessary for a sound conclusion. Fall back to `rg` and focused reads for unsupported languages, build files, generated files, exact strings, or stale graph data.
