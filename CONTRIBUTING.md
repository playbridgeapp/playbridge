# Contributing to PlayBridge

Thank you for your interest in contributing to PlayBridge! We welcome contributions from everyone.

## Getting Started

1.  **Fork the repository**: Click the "Fork" button at the top right of the repository page.
2.  **Clone your fork**:
    ```bash
    git clone https://github.com/your-username/PlayBridge.git
    cd PlayBridge
    ```
3.  **Set up the environment**:
    - See the component prerequisites in [Build Instructions](README.md#build-instructions) and the environment details in [AGENTS.md](AGENTS.md#environment).
    - **Android**: Install SDK 37 and a JDK supported by Gradle; the phone build resolves its JDK 21 toolchain. Sync the owning Android project (`mobile/android/` or `tv/android/`), not the repository root.
    - **Rust, Flutter, pnpm, Xcode**: For toolchains across Rust core/proxies, Flutter Desktop, browser extension/web (pnpm), and Apple (Swift/Xcode), see [AGENTS.md](AGENTS.md).

## Making Changes

1.  **Create a branch**:
    ```bash
    git checkout -b feature/my-new-feature
    ```
2.  **Make your changes**: Implement your feature or fix.
3.  **Run tests**: Ensure all tests pass.
    ```bash
    # Run tests for the components you changed (see AGENTS.md for details):
    (cd mobile/android && ./gradlew :app:testFossDebugUnitTest :shared:testAndroidHostTest)
    (cd tv/android && ./gradlew :player:app:testFossDebugUnitTest :shared:testAndroidHostTest)
    (cd extension && pnpm typecheck && pnpm test)
    (cd desktop && flutter test)
    cargo test --workspace --locked
    ```
4.  **Commit your changes**:
    ```bash
    git commit -m "feat: Add my new feature"
    ```
    Please follow [Conventional Commits](https://www.conventionalcommits.org/).

## Submitting a Pull Request

1.  **Push your changes**:
    ```bash
    git push origin feature/my-new-feature
    ```
2.  **Open a Pull Request**: Go to the original repository and click "New Pull Request".
3.  **Fill out the template**: Describe your changes clearly.

## Code Style

- Follow the conventions and formatting for the language/toolchain you touch (see [AGENTS.md](AGENTS.md#build-and-test)):
  - **Android / Kotlin**: Official Kotlin coding conventions. Run `./gradlew :app:lintFossDebug`.
  - **Rust**: Format with `cargo fmt --all -- --check` and lint with `cargo clippy --workspace --all-targets --locked -- -D warnings`.
  - **Flutter / Desktop**: Analyze with `flutter analyze` from `desktop/`.
  - **Extension & Web**: Verify with `pnpm typecheck` or `pnpm check`.

## Questions?

If you have any questions about contributing or need help getting started, please reach out to us at [hello@playbridge.app](mailto:hello@playbridge.app).

## License

PlayBridge's own code is licensed under [MPL-2.0](LICENSE). Third-party code keeps
its existing licences; preserve notices and identify any third-party material you add.
Read [TRADEMARKS.md](TRADEMARKS.md) before publishing a fork: use a different name
and icon and do not imply endorsement.

## Contributor licence agreement

Before a contribution is merged, its pull request author must sign the individual
[CLA](CLA.md). You keep your copyright and grant the maintainer rights to use,
modify, sublicense and distribute your contribution under MPL-2.0 or another
licence, including future dual or commercial licensing. In return, the CLA promises that your contribution
stays available under an OSI-approved open source licence. See
[docs/licensing.md](docs/licensing.md) for the reasons behind the licence and the CLA.

Sign by posting this exact comment on your pull request:

> I have read the CLA Document and I hereby sign the CLA

The workflow checks only the PR author. Commit co-author trailers, including AI
tool attribution, do not require separate signatures. The project owner and the
listed automation bots are exempt. A stored signature covers later contributions
under the same CLA version; comment `recheck` to rerun the check.

Signatures (GitHub identity, comment/time and PR reference) are stored publicly in
`signatures/cla-v1.json` on the repository's `cla-signatures` branch. Do not submit
an employer's or another person's work unless you have authority to grant these
rights. See the CLA for details.
