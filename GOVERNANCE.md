# Governance - Ligero Framework

This document describes how the Ligero Framework project is governed and how contributors can participate in decision-making.

## Roles and Responsibilities

### Maintainers
Maintainers are responsible for:
- Code review and approval of pull requests
- Managing releases and versioning
- Setting the technical direction of the project
- Resolving conflicts in the community
- Enforcing architectural principles (see CONTRIBUTING.md)

Maintainers are added by consensus among existing maintainers.

### Reviewers
Reviewers assist maintainers by:
- Providing code feedback
- Testing and validating changes
- Improving documentation
- Helping triage issues

Reviewers are nominated by maintainers based on consistent, quality contributions.

### Contributors
Contributors are community members who:
- Report bugs and request features
- Propose improvements
- Submit pull requests
- Help with documentation and examples

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the contribution workflow.

## Decision Making

### Minor Changes
Bug fixes, documentation updates, and small enhancements are approved by a single maintainer during code review.

### Major Changes
Significant architectural changes, dependency additions, or API changes require:
1. An issue or RFC discussion with the maintainer(s)
2. Clear rationale and design
3. Community feedback period (≥1 week)
4. Consensus among maintainers before implementation

### Release Decisions
Maintainers decide the release schedule and version bumping based on:
- Semantic versioning (MAJOR.MINOR.PATCH)
- Stability of the codebase
- Community feedback
- Roadmap alignment (see [ROADMAP.md](ROADMAP.md))

## Conflicts and Escalation

If a contributor disagrees with a decision:
1. Discuss in the PR or issue thread respectfully
2. Request a second maintainer's opinion
3. If still unresolved, file an issue tagged `discussion` for broader community input

All disputes are handled according to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Architectural Principles

All changes must adhere to the architectural rules in [CONTRIBUTING.md](CONTRIBUTING.md):

1. **No new dependencies in `ligero-core`** — Keep it lean (only slf4j-api)
2. **Features are middleware or SPI adapters** — Never core-only changes
3. **Single responsibility** — Each module has one reason to change
4. **Testability** — Every change ships with tests (80%+ coverage in core)
5. **Documentation** — APIs, patterns, and non-obvious behaviors are documented

## Community Channels

- **GitHub Issues**: Bug reports, feature requests, and discussions
- **GitHub Discussions** (if enabled): General questions and idea exploration
- **Security Issues**: See [SECURITY.md](SECURITY.md) for responsible disclosure

## Branch Protection and CI/CD

The `main` branch is protected with:
- Mandatory code review (≥1 approval)
- All CI/CD checks must pass (tests, coverage, linting)
- Branches must be up to date before merge
- All conversations must be resolved
- Force pushes are disabled

See below for protection details.

## Release Process

### Versioning
Ligero follows [Semantic Versioning](https://semver.org/):
- **MAJOR**: Breaking changes (e.g., API changes, removed features)
- **MINOR**: New features (backwards compatible)
- **PATCH**: Bug fixes (backwards compatible)

Example: `v0.2.0` = Major 0 (in development), Minor 2, Patch 0

### Release Steps
1. Maintainer creates a PR bumping version in `gradle.properties`
2. Update `CHANGELOG.md` under "Unreleased" → new version
3. Merge to `main` (triggers CI)
4. Maintainer tags the commit: `git tag -a v0.3.0 -m "Release v0.3.0"`
5. Push tag: `git push origin v0.3.0`
6. GitHub Action publishes to Maven Central (when available)
7. GitHub Release is auto-generated from CHANGELOG.md

### Release Cadence
- **Regular releases**: Monthly or as needed
- **Security patches**: Released ASAP
- **Beta/RC versions**: Marked as pre-release in GitHub

## Becoming a Maintainer

As Ligero grows, we welcome new maintainers. Candidates should:
- Have 10+ merged PRs showing strong understanding
- Demonstrate architectural alignment
- Show commitment to code quality and community
- Receive approval from existing maintainers

Maintainers are added by consensus (≥2 existing maintainers).

## Transparency

This project values transparency:
- Design decisions are documented in issues or RFCs
- Roadmap is public ([ROADMAP.md](ROADMAP.md))
- Rejected proposals are explained
- Feedback is sought regularly from the community
