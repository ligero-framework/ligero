# Releasing Ligero

How to cut a coordinated release across the four repositories:

| Repo | What ships | Where |
|---|---|---|
| **[ligero](https://github.com/ligero-framework/ligero)** | the framework modules | Maven Central (`com.ligeroframework:*`) |
| **[ligero-cli](https://github.com/ligero-framework/ligero-cli)** | the `ligero` scaffolding CLI | GitHub Release (dist) |
| **[ligero-examples](https://github.com/ligero-framework/ligero-examples)** | runnable example apps | Git tag |
| **[ligero-docs](https://github.com/ligero-framework/ligero-docs)** | the documentation site | GitHub Pages |

## Versioning

- **Semantic Versioning** (`MAJOR.MINOR.PATCH`). The **release tag drives the
  published version** (tag `vX.Y.Z` → artifacts `X.Y.Z`), so `gradle.properties`
  is never edited by hand for a release; `main` always carries the next
  `-SNAPSHOT`, bumped automatically after each release (see §1).
- CLI and examples **track the framework version** they target.
- Docs are continuously deployed; optionally snapshot a versioned copy on a
  MAJOR/MINOR bump (see §4).

## Release order

Dependencies flow downhill — release in this order:

1. **Framework** → Maven Central (everything else depends on it)
2. **CLI** and **examples** (bump to the released version, verify)
3. **Docs** (reference the new version, announce)

---

## 0. Prerequisites (one-time)

- A **Central Portal** (Sonatype) account with the `com.ligeroframework` namespace
  verified — see [MAVEN_CENTRAL_PUBLISHING.md](MAVEN_CENTRAL_PUBLISHING.md).
- A **GPG key** published to a public keyserver.
- Secrets configured on the `ligero` repo:
  - `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` (Central Portal token)
  - `SIGNING_KEY` (ASCII-armored private key), `SIGNING_PASSWORD`

## 1. Framework release (`ligero`)

**Pre-flight:** `main` is green (CI + coverage) and the `CHANGELOG.md`
*Unreleased* section is complete.

The release is **tag-driven** — creating the GitHub Release does everything:

1. **CHANGELOG:** rename `## [Unreleased]` → `## [x.y.z] — YYYY-MM-DD` and add a
   fresh empty *Unreleased* block on top (a small PR, merged to `main`). This is
   the only content change; you do **not** touch `version` in `gradle.properties`.
2. **Create the GitHub Release** from `main` with tag `vX.Y.Z`
   (`git tag -a vX.Y.Z -m "Ligero X.Y.Z" && git push origin vX.Y.Z`, then publish
   the Release — notes are auto-categorized via
   [`.github/release.yml`](.github/release.yml)).
3. That's it. The [*Publish to Maven Central*](.github/workflows/release.yml)
   workflow fires on the published Release and:
   - publishes the signed artifacts as **`X.Y.Z`** (version read from the tag,
     `-Pversion=${GITHUB_REF_NAME#v}`) and closes/releases the staging repo;
   - **bumps `main`** to `X.Y.(Z+1)-SNAPSHOT` and pushes the
     `chore: begin …` commit — so everyday builds target the next version with
     no manual edit.

   Verify on [central.sonatype.com](https://central.sonatype.com) and, once
   synced, at `https://repo1.maven.org/maven2/com/ligeroframework/`.

> **Notes.** Pre-releases (a Release marked *pre-release*, or a non-`X.Y.Z` tag)
> publish but **skip** the snapshot bump. If `main` is a protected branch, give
> the bump job a `RELEASE_BOT_TOKEN` secret (a PAT or GitHub-App token allowed to
> push to `main`); otherwise it uses the default `GITHUB_TOKEN`. CI also
> auto-publishes **SNAPSHOTs** from `main` whenever the `MAVEN_CENTRAL_*` secrets
> exist.

## 2. CLI release (`ligero-cli`)

1. Bump the framework version the generated projects target, and the CLI's own
   version.
2. `./gradlew build` (tests green); smoke-test `ligero new` → the generated app
   boots and its tests pass.
3. Tag `vX.Y.Z` and attach the distributable (`./gradlew installDist` /
   `distZip`) to a GitHub Release.
4. *(Future)* publish via Homebrew / Scoop / SDKMAN.

## 3. Examples release (`ligero-examples`)

1. Bump the Ligero dependency from `-SNAPSHOT` to `X.Y.Z`.
2. Run every example (`./gradlew test` + boot each) against the **released**
   artifacts from Maven Central.
3. Tag `vX.Y.Z` so a checkout of the tag matches the released framework.

## 4. Docs release (`ligero-docs`)

- Merges to `main` **auto-deploy** to GitHub Pages
  ([`deploy.yml`](https://github.com/ligero-framework/ligero-docs/blob/main/.github/workflows/deploy.yml)).
- Update version references, the benchmarks (if numbers changed), and post the
  release announcement.
- **Optional versioned docs** on a MAJOR/MINOR bump, so old versions stay
  browsable:
  ```bash
  npm run docusaurus docs:version X.Y.Z
  ```

---

## Release checklist

Copy into the release tracking issue:

- [ ] Framework CI green on `main`
- [ ] `CHANGELOG.md` dated; version bumped; tag `vX.Y.Z` pushed
- [ ] Published to Maven Central + verified on `repo1.maven.org`
- [ ] GitHub Release created
- [ ] CLI bumped, tested, tagged, dist attached
- [ ] Examples bumped, verified against released artifacts, tagged
- [ ] Docs updated + deployed
- [ ] Next `-SNAPSHOT` set on framework `main`
- [ ] Announcement (GitHub Discussions / README badge)

## Hotfix (patch) releases

Branch `hotfix/x.y.(z+1)` from the release **tag** (not `main`), apply the fix
only, then follow steps 1–8. Cherry-pick the fix back to `main` if needed.

## 5. Automation (recommended)

Not yet wired. Add `.github/workflows/release.yml` triggered on version tags so
a `git push origin vX.Y.Z` performs the publish and GitHub Release:

```yaml
name: Release
on:
  push:
    tags: ['v*']
permissions:
  contents: write
jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - uses: gradle/actions/setup-gradle@v4
      - name: Publish to Maven Central
        run: ./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository
        env:
          MAVEN_CENTRAL_USERNAME: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          MAVEN_CENTRAL_PASSWORD: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.SIGNING_KEY }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.SIGNING_PASSWORD }}
      - uses: softprops/action-gh-release@v2   # GitHub Release from the tag
```

> `closeAndReleaseSonatypeStagingRepository` is provided by the
> `io.github.gradle-nexus.publish-plugin` already configured in `build.gradle`.
