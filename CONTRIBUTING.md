# Contributing to turbomesh-computing-machine

Thank you for considering a contribution to **turbomesh-computing-machine**! We welcome bug reports, feature requests, documentation improvements, and code contributions.

Please take a moment to read this guide before opening an issue or submitting a pull request.

---

## Table of Contents

1. [Code of Conduct](#code-of-conduct)
2. [Getting Started](#getting-started)
3. [How to Report a Bug](#how-to-report-a-bug)
4. [How to Request a Feature](#how-to-request-a-feature)
5. [Development Workflow](#development-workflow)
6. [Pull Request Process](#pull-request-process)
7. [Code Style](#code-style)
8. [Commit Message Guidelines](#commit-message-guidelines)

---

## Code of Conduct

This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating you agree to abide by its terms. Please report any unacceptable behaviour to the project maintainers.

---

## Getting Started

1. **Fork** the repository on GitHub.
2. **Clone** your fork locally:
   ```bash
   git clone https://github.com/<your-username>/turbomesh-computing-machine.git
   cd turbomesh-computing-machine
   ```
3. Create a new **feature branch** from `main`:
   ```bash
   git checkout -b feat/my-new-feature
   ```
4. Make your changes, add tests where appropriate, then push and open a pull request.

---

## How to Report a Bug

Use the **[Bug Report](.github/ISSUE_TEMPLATE/bug_report.md)** issue template. Before opening a new issue, please:

- Search [existing issues](https://github.com/gdev6145/turbomesh-computing-machine/issues) to avoid duplicates.
- Include a minimal, reproducible example.
- Provide device model, Android version, and app version.

---

## How to Request a Feature

Use the **[Feature Request](.github/ISSUE_TEMPLATE/feature_request.md)** issue template. Please describe:

- The problem you are trying to solve.
- How the proposed feature would help.
- Any alternatives you have considered.

---

## Development Workflow

```bash
# Install/sync dependencies
./gradlew dependencies

# Run lint
./gradlew lint

# Run unit tests
./gradlew test

# Run instrumented tests (device required)
./gradlew connectedAndroidTest

# Build debug APK
./gradlew assembleDebug
```

Make sure all checks pass before opening a pull request.

---

## Pull Request Process

1. Ensure your branch is up to date with `main` before opening the PR.
2. Fill in the **pull request template** completely.
3. Link any related issues using `Closes #<issue-number>` in the PR description.
4. All CI checks (lint, unit tests) must pass.
5. At least one maintainer approval is required before merging.
6. Squash or rebase commits if requested by a reviewer.

---

## Code Style

- **Kotlin**: Follow the [Kotlin coding conventions](https://kotlinlint.io) and the Android Kotlin style guide.
- **XML layouts**: Use `snake_case` for resource IDs and file names.
- **Java**: Follow the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) if Java files are present.
- Run `./gradlew lint` before submitting – the build must be lint-clean.

---

## Commit Message Guidelines

We use [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <short summary>

[optional body]

[optional footer(s)]
```

**Types:** `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `perf`

**Examples:**
```
feat(mesh): add relay node configuration screen
fix(scan): handle BLE permission denial gracefully
docs: update README with configuration section
```

---

Thank you for helping make turbomesh-computing-machine better! 🎉
