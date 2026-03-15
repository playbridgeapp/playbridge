## 2024-05-24 - [Android Compose] **Learning:** Repeated `filter` inside `items` block of `LazyColumn` causes O(N*M) list operation on every recomposition. **Action:** Pre-compute maps using `remember { list.groupBy { ... } }` outside of `LazyColumn` and access elements in `items` block.
## 2024-05-24 - [Kotlin Sequences] **Learning:** Repeated `.map { ... }.sortedByDescending { ... }.firstOrNull()` is an expensive multi-pass operation. **Action:** Refactor chain to `.maxByOrNull { ... }` for a single-pass O(N) execution.

## 2024-05-28 - [Android] Interaction-based reads and List vs Sequence memory allocations
**Learning:** I learned two crucial lessons:
1. Moving lazy, interaction-based operations (like SharedPreferences reads) from an `onClick` callback into an eager `remember` block outside a Compose `LazyColumn` is an anti-pattern. It blocks the main thread during composition and creates stale state.
2. When parsing large files line-by-line, chaining `.map` and `.filter` after `.lines()` creates massive intermediate lists. While `.lines().asSequence()` avoids intermediate lists in the chain, `.lines()` still initially allocates every line in memory. `.lineSequence()` is required for true constant O(1) memory parsing.
**Action:** When optimizing Compose, distinguish between eager state needed for rendering and deferred state needed for interaction. When optimizing large string parsing, use `.lineSequence()` instead of `.lines()` or `.lines().asSequence()`.
