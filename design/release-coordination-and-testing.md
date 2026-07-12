# Design notes — release coordination, docs versioning, and testing

> Status: **analysis / decisions**. Answers three operational questions:
> how the CLI stays in sync with the framework version, how the docs are
> versioned, and whether to adopt Gradle `testFixtures`.

## 1. Framework ↔ CLI version coordination

### 1.1 The problem

`ligero new` generates projects that depend on a *specific* framework version.
That version lives in one place — `Templates.LIGERO_VERSION` — and is surfaced
as the `ligeroVersion` ext property in the generated `build.gradle`, so every
Ligero dependency (`ligero-core`, `ligero-devtools`, `ligero-processor`, …)
reads from a single variable:

```gradle
ext { ligeroVersion = '0.6.0' }        // <- Templates.LIGERO_VERSION
// ...
implementation "com.ligeroframework:ligero-core:$ligeroVersion"
```

Two failure modes bound the problem:

- **If the constant lags** the latest published framework, `ligero new`
  scaffolds projects on an **old** framework — users miss fixes and features and
  file "why am I on 0.5?" issues.
- **If the constant points at an *unpublished* version**, generated projects
  **don't resolve at all** — Maven Central has no artifact, `gradle build`
  fails on a fresh checkout. This is the worse failure: a broken first
  experience.

So the invariant is: **`LIGERO_VERSION` must always name a version that is
already published to Maven Central, and should be the latest such version.**

### 1.2 Two rejected alternatives

- **Resolve "latest" dynamically at generate time** (e.g. query Central for the
  newest version and write *that*). Rejected: it makes `ligero new` output
  **non-deterministic** (two runs a week apart produce different projects) and
  **network-dependent** (offline or air-gapped users get nothing, or a
  failure). Scaffolding should be reproducible and work offline.
- **Use a dynamic Gradle range** in the template (`ligero-core:0.+` or
  `latest.release`). Rejected for the same reasons *inside the generated app*:
  it makes the **user's** build non-reproducible and surprises them with
  silent upgrades. Pinning an exact version is the correct default for a
  scaffolded project.

### 1.3 Decision: pin + automate the bump

Keep pinning an **exact, published** version (reproducible, offline-friendly),
and remove the manual maintenance by **automating the bump** through CI.

The key insight is to **decouple two things that look like one**:

| Concept | What it is | Cadence |
|---|---|---|
| **Targeted framework version** (`LIGERO_VERSION`) | which framework a *new project* pins | bump on **every** framework release |
| **CLI release** (a tagged `ligero` binary) | a new version of the CLI tool itself | cut only on framework **MINOR/MAJOR**, or when the CLI's own code changes |

A framework release does **not** require a 1:1 CLI release. Most framework
releases (especially PATCHes) should only move the *targeted version* — a
one-line change merged via PR — while the CLI binary people have installed keeps
working and keeps scaffolding correct projects.

### 1.4 The mechanism (implemented)

When the framework's publish workflow finishes uploading `vX.Y.Z` to Maven
Central, it fires a cross-repo `repository_dispatch` at `ligero-cli`. A CLI
workflow receives it, rewrites `LIGERO_VERSION`, and **opens a PR** (never a
direct push — `main` is protected).

**Framework side** — a `notify-cli` job, `needs: publish`:

```yaml
  notify-cli:
    needs: publish
    if: ${{ !github.event.release.prerelease }}
    runs-on: ubuntu-latest
    steps:
      - name: Dispatch framework-released to ligero-cli
        if: ${{ secrets.RELEASE_BOT_TOKEN != '' }}
        run: |
          version="${GITHUB_REF_NAME#v}"
          curl -sf -X POST \
            -H "Authorization: Bearer ${{ secrets.RELEASE_BOT_TOKEN }}" \
            -H "Accept: application/vnd.github+json" \
            https://api.github.com/repos/ligero-framework/ligero-cli/dispatches \
            -d "{\"event_type\":\"framework-released\",\"client_payload\":{\"version\":\"${version}\"}}"
```

**CLI side** — `.github/workflows/framework-bump.yml`:

```yaml
on:
  repository_dispatch:
    types: [framework-released]
  workflow_dispatch:                       # manual fallback
    inputs: { version: { required: true } }
jobs:
  bump:
    runs-on: ubuntu-latest
    permissions: { contents: write, pull-requests: write }
    steps:
      - uses: actions/checkout@v4
      - id: v
        run: |
          v="${{ github.event.client_payload.version || github.event.inputs.version }}"
          v="${v#v}"
          [[ "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "skip=true" >> "$GITHUB_OUTPUT"; exit 0; }
          echo "version=$v" >> "$GITHUB_OUTPUT"
      - if: steps.v.outputs.skip != 'true'
        run: |
          sed -i -E 's/(LIGERO_VERSION = ")[^"]+(")/\1${{ steps.v.outputs.version }}\2/' \
            src/main/java/com/ligero/cli/Templates.java
      - if: steps.v.outputs.skip != 'true'
        uses: peter-evans/create-pull-request@v6
        with:
          token: ${{ secrets.RELEASE_BOT_TOKEN || secrets.GITHUB_TOKEN }}
          base: main
          branch: chore/target-ligero-${{ steps.v.outputs.version }}
          title: "chore: generated projects target Ligero ${{ steps.v.outputs.version }}"
          commit-message: "chore: generated projects target Ligero ${{ steps.v.outputs.version }}"
```

End-to-end flow:

```
tag v0.6.1 on ligero
      │
      ▼
publish job  ──►  Maven Central has 0.6.1
      │
      ▼
notify-cli  ──(repository_dispatch: framework-released, "0.6.1")──►  ligero-cli
                                                                          │
                                                                          ▼
                                                          framework-bump opens PR
                                                          "target Ligero 0.6.1"
                                                                          │
                                                     (checks pass, merge — optionally auto-merge)
                                                                          ▼
                                                    ligero new now pins 0.6.1
```

### 1.5 When to actually cut a CLI release

The bump PR keeps the *targeted version* current without releasing the CLI.
Cut a real CLI release (a new tag → native binaries + installers, see
`RELEASING.md`) only when:

- the framework had a **MINOR or MAJOR** bump worth surfacing in the tool
  (new templates, new `--flags`, new generators to match new framework APIs); or
- the **CLI's own code** changed (bug fix, new command, template change).

Do **not** cut a CLI release for a framework **PATCH** — the already-installed
CLI keeps scaffolding correctly once the bump PR merges. This keeps CLI release
noise proportional to actual CLI change, and users don't get a "new CLI" prompt
for a framework patch that didn't affect the tool.

### 1.6 Ordering, safety, and fallback

- **Publish-before-notify.** The dispatch fires only after
  `closeAndReleaseSonatypeStagingRepository` succeeds, so `LIGERO_VERSION` can
  never be bumped to a version that isn't on Central. (Central's release can lag
  a few minutes behind the API call; the CLI PR's own build is what would catch
  a not-yet-propagated artifact, and it can simply be re-run.)
- **Pre-releases are skipped** (`if: !prerelease`), so an `-rc` tag never moves
  the targeted version.
- **Non-`X.Y.Z` tags are ignored** by the CLI workflow's regex guard.
- **Token.** A cross-repo `repository_dispatch` **cannot** use the default
  `GITHUB_TOKEN`; it needs a `RELEASE_BOT_TOKEN` (PAT or GitHub-App token) with
  access to `ligero-cli`. The CLI side also prefers that token so the opened
  PR's checks run (PRs opened by `GITHUB_TOKEN` don't trigger CI).
- **Manual fallback.** Until the token is wired, the same bump runs from the
  Actions tab via `workflow_dispatch` (type the version), or `LIGERO_VERSION`
  is edited by hand — a one-line change on the release checklist.

## 2. Documentation versioning

**Today.** `ligero-docs` continuously deploys `main` to the site (always
"latest"). There's no per-version snapshot, so once `0.7` lands, the `0.6` docs
are gone.

**Decision.** Adopt **Docusaurus versioned docs**, snapshotting on each
framework **MINOR/MAJOR** (not PATCH — patches rarely change the docs and each
version multiplies build output):

```bash
npm run docusaurus docs:version 0.6      # freezes today's docs as "0.6"
```

- `docs/` stays the **Next** (unreleased) version; `versioned_docs/version-0.6/`
  is the frozen snapshot; a version dropdown appears in the navbar.
- **Automate** on the framework release (MINOR/MAJOR) via the same
  `repository_dispatch`: a `ligero-docs` workflow runs `docs:version <MAJOR.MINOR>`
  and opens a PR.
- Keep only the **last few** minors browsable to bound build time.

Rationale: a framework's public API is versioned, so its docs must be too —
readers on `0.6` shouldn't see `0.7`-only APIs. Snapshotting at MINOR keeps the
count small.

## 3. Gradle `testFixtures` — analysis & decision

**What it is.** The `java-test-fixtures` plugin lets a module publish a separate
`testFixtures` variant (source set `src/testFixtures/java`) with shared test
helpers, consumed as `testImplementation(testFixtures(project(":x")))` (or, when
published, `testFixtures("com.ligeroframework:ligero-core:…")`).

**What Ligero already has.** **`ligero-test`** — a *published* module whose
`LigeroTest` boots an app on an ephemeral port and drives it over real HTTP.
That is exactly the "shared testing helpers" story, but delivered as a normal,
versioned, documented dependency (`testImplementation
"com.ligeroframework:ligero-test:$ligeroVersion"`), already covered in the
[Testing guide](https://doc.ligeroframework.com/guides/testing).

**Decision: do not adopt `testFixtures` for the public testing API.**

- `ligero-test` is simpler for users — a dependency, not a `testFixtures(...)`
  coordinate — and it versions and documents cleanly.
- Publishing `testFixtures` variants adds POM/metadata surface and a second way
  to do the same thing, against the framework's "one obvious path" ethos.
- `testFixtures` *would* help **internally** (sharing helpers across the
  framework's own module tests without a published artifact). That's a build-time
  convenience with real but small value; revisit only if internal test
  duplication becomes painful. It is **not** something apps should need.

**Documentation.** The public testing story is `ligero-test` (unit tests with
plain JUnit + the reflection-free `ctx.bodyValidator`, end-to-end with
`LigeroTest`). The Testing guide should also note that **devtools** is a
debugging aid during test-driven development, and that generated projects ship a
ready `ApplicationTest`. No `testFixtures` section is needed.
