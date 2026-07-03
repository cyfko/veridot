---
title: Contributing to Veridot
description: Guidelines for contributing to the Veridot open source project.
keywords: [veridot, contributing, open source]
sidebar_position: 95
---

# Contributing to Veridot

We welcome contributions to Veridot! Here's how to get started.

## Code of Conduct

Please read and follow our [Code of Conduct](https://github.com/cyfko/veridot/blob/main/CODE_OF_CONDUCT.md).

## Contributor License Agreement (CLA)

To accept your pull request, we need you to submit a CLA. You only need to do this once. If you've done this for another Kunrin open source project, you're good to go.

Complete your CLA here: [https://code.kunrin.com/cla](https://code.kunrin.com/cla)

## Development Setup

### Prerequisites

- **Java 25+** (for veridot-core)
- **Maven 3.9+** (wrapper included)
- **Docker** (for integration tests via Testcontainers)

### Build and Test

```bash
# Clone the repository
git clone https://github.com/cyfko/veridot.git
cd veridot/java

# Run unit tests (no external dependencies)
./mvnw test -pl veridot-core --no-transfer-progress

# Run integration tests (requires Docker)
./mvnw test -pl veridot-tests -am --no-transfer-progress
```

### Test Results

**v4.0.1**: 98 unit tests, 34 integration tests, 0 failures, 0 errors.

## Pull Request Process

1. Fork the repository and create your branch from `main`.
2. Ensure all tests pass (`./mvnw test`).
3. Update documentation if you change any public API.
4. Submit your pull request with a clear description of the change.

## Reporting Issues

Use [GitHub Issues](https://github.com/cyfko/veridot/issues) to report bugs or request features.

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](https://github.com/cyfko/veridot/blob/main/LICENSE).
