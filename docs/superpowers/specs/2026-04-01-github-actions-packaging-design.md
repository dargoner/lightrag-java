# GitHub Actions Packaging And Release Design

**Goal:** Add GitHub-hosted automation so the repository builds on every push/PR and can publish release artifacts to Maven Central from GitHub.

## Scope

- Add a CI workflow that runs Gradle build on `push` and `pull_request`.
- Add a release workflow that publishes `lightrag-core` and `lightrag-spring-boot-starter` to Maven Central on version tags or manual dispatch.
- Make Gradle signing detection compatible with in-memory PGP keys that have no passphrase.

## Design

### CI workflow

- File: `.github/workflows/ci.yml`
- Trigger on pushes to `main` and pull requests.
- Use Ubuntu runner with Temurin 17.
- Use Gradle cache to keep build latency down.
- Run `./gradlew --no-daemon build`.

### Release workflow

- File: `.github/workflows/release.yml`
- Trigger on tags like `v0.1.0` and `workflow_dispatch`.
- Resolve release version from the tag name or dispatch input.
- Export Maven Central credentials and signing values through `ORG_GRADLE_PROJECT_*` environment variables.
- Run:
  `./gradlew --no-daemon -PreleaseVersion=<version> :lightrag-core:publishAndReleaseToMavenCentral :lightrag-spring-boot-starter:publishAndReleaseToMavenCentral`

### Signing compatibility

- Files:
  - `lightrag-core/build.gradle.kts`
  - `lightrag-spring-boot-starter/build.gradle.kts`
- Current logic requires both `signingInMemoryKey` and `signingInMemoryKeyPassword`.
- Change it so an in-memory key alone is enough to enable signing, which supports keys without passphrases.

## Risks And Mitigations

- Release without configured secrets will fail fast at workflow runtime.
- Tag-triggered publishing can accidentally release if someone pushes a tag by mistake.
  Mitigation: only publish on explicit `v*` tags or manual dispatch input.
- Full `build` may be slower than module-scoped checks, but it gives stronger safety for CI.
