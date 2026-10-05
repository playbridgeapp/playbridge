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
    - See the component prerequisites in [Build Instructions](README.md#build-instructions).
    - For Android, install SDK 37 and a JDK supported by Gradle; the phone build resolves its JDK 21 toolchain.
    - Sync the owning Android project (`mobile/android/` or `tv/android/`), not the repository root.

## Making Changes

1.  **Create a branch**:
    ```bash
    git checkout -b feature/my-new-feature
    ```
2.  **Make your changes**: Implement your feature or fix.
3.  **Run tests**: Ensure all tests pass.
    ```bash
    # From the repository root: run the tests for the components you changed.
    (cd mobile/android && ./gradlew :app:testFossDebugUnitTest :shared:testAndroidHostTest)
    (cd tv/android && ./gradlew :player:app:testFossDebugUnitTest :shared:testAndroidHostTest)
    (cd extension && pnpm typecheck && pnpm test)
    (cd desktop && flutter test)
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

- We use Kotlin's official coding conventions.
- Please run the linter before submitting:
    ```bash
    # Run only the relevant Android component.
    (cd mobile/android && ./gradlew :app:lintFossDebug)
    (cd tv/android && ./gradlew :player:app:lintFossDebug)
    ```

## Questions?

If you have any questions about contributing or need help getting started, please reach out to us at [playbridgeapp@gmail.com](mailto:playbridgeapp@gmail.com).

## License

By contributing, you agree that your contributions will be licensed under the GNU General Public License v3.0.
