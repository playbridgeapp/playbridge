---
name: code-review-graph-workflow
description: Use PlayBridge's code-review-graph MCP for codebase exploration, debugging, change review, impact analysis, test discovery, and scoped refactoring. Use when structural code relationships or blast radius matter; fall back to focused text/file inspection when the graph lacks coverage.
---

# Code-review graph workflow

Start with `get_minimal_context(task="...")`, then select only the tools needed for the task.

## Explore

- Use `get_architecture_overview` or `list_communities` for broad structure.
- Use `semantic_search_nodes` to locate code and `query_graph` for callers, callees, imports, and children.
- Use `list_flows`/`get_flow` for execution paths.

## Debug

- Locate the failing behavior with `semantic_search_nodes`.
- Trace callers/callees or flows, then compare recent changes with `detect_changes` when regression history matters.
- Confirm hypotheses with focused source reads, logs, and tests; graph relationships are evidence, not proof of runtime behavior.

## Review

- Use `detect_changes` against the correct base, followed by `get_affected_flows` or `get_impact_radius` for risky changes.
- Query `tests_for` on high-risk entities and inspect exact changed lines before reporting a finding.
- Report actionable defects first with file/line evidence. Do not inflate generic coverage gaps into defects.

## Refactor

- Analyze the requested symbol/files with impact and relationship queries before editing.
- Use rename preview/apply only for a requested rename and review the generated diff.
- Use dead-code or broad suggestion modes only when the user explicitly requests an audit; do not expand a scoped refactor.
- Re-run change detection and focused tests after edits.

Use minimal detail initially, but increase detail and tool calls whenever necessary for a sound conclusion. Fall back to `rg` and focused reads for unsupported languages, build files, generated files, exact strings, or stale graph data.
