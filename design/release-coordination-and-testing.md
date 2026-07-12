# Design notes — release coordination, docs versioning, and testing

> Status: **analysis / decisions**. Answers three operational questions:
> how the CLI stays in sync with the framework version, how the docs are
> versioned, and whether to adopt Gradle `testFixtures`.

## 1. Framework ↔ CLI version coordination

**Problem.** `ligero new` generates projects that depend on a *specific*
framework version (`Templates.LIGERO_VERSION`, surfaced as the `ligeroVersion`
ext property in the generated `build.gradle`). If that constant lags behind the
latest published framework, new projects pull an old framework; if it points at
an *unpublished* version, they don't resolve at all (Central has no artifact
yet). So the CLI must always pin the **latest published** framework version.

**Decision.** Keep pinning (reproducible, offline-friendly) and **automate the
bump**, rather than resolving "latest" dynamically at generate time (which makes
`ligero new` output non-deterministic and network-dependent).

- **A framework release does not require a 1:1 CLI release.** The CLI only needs
  a *new release* when its own behaviour changes. But every framework release
  should update the version new projects target.
- **Mechanism:** when the framework release workflow finishes publishing
  `vX.Y.Z`, it fires a `repository_dispatch` at `ligero-cli`; a CLI workflow
  bumps `LIGERO_VERSION`/`ligeroVersion` to `X.Y.Z` and **opens a PR** (same
  protected-branch pattern as the snapshot bump).

Framework side (add to the publish job, after a successful publish):

```yaml
      - name: Tell ligero-cli about the new version
        run: |
          curl -sf -X POST \
            -H "Authorization: Bearer ${{ secrets.RELEASE_BOT_TOKEN }}" \
            -H "Accept: application/vnd.github+json" \
            https://api.github.com/repos/ligero-framework/ligero-cli/dispatches \
            -d '{"event_type":"framework-released","client_payload":{"version":"'"${GITHUB_REF_NAME#v}"'"}}'
```

CLI side (`.github/workflows/framework-bump.yml`):

```yaml
on:
  repository_dispatch:
    types: [framework-released]
jobs:
  bump:
    runs-on: ubuntu-latest
    permissions: { contents: write, pull-requests: write }
    steps:
      - uses: actions/checkout@v4
      - run: |
          v="${{ github.event.client_payload.version }}"
          sed -i -E "s/LIGERO_VERSION = \"[^\"]+\"/LIGERO_VERSION = \"$v\"/" \
            src/main/java/com/ligero/cli/Templates.java
      - uses: peter-evans/create-pull-request@v6
        with:
          token: ${{ secrets.RELEASE_BOT_TOKEN }}
          branch: chore/framework-${{ github.event.client_payload.version }}
          title: "chore: target Ligero ${{ github.event.client_payload.version }}"
          commit-message: "chore: generated projects target Ligero ${{ github.event.client_payload.version }}"
```

**When to cut a CLI release.** On a framework **MINOR/MAJOR** bump (new
capabilities worth surfacing) or whenever the CLI itself changes — not on every
framework **PATCH**. The bump PR keeps `LIGERO_VERSION` current between CLI
releases, and the CLI's own release remains tag-driven (see `RELEASING.md`).

> Requires a `RELEASE_BOT_TOKEN` with `repo` scope on both repositories. Until
> it's wired, `LIGERO_VERSION` is bumped by hand as part of the release
> checklist — a one-line change.

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
