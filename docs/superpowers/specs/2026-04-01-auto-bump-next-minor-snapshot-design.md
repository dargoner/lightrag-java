# Auto Bump Next Minor Snapshot After Release Design

**Goal:** After a successful formal release, automatically advance the repository default version to the next minor snapshot, for example `0.1.0` -> `0.2.0-SNAPSHOT`.

## Scope

- Store the repository default development version in `gradle.properties`.
- Keep release publishing driven by `-PreleaseVersion`.
- Extend the GitHub release workflow so a successful release updates the default branch version and pushes a commit back to `main`.

## Design

### Version source

- Add `projectVersion=<snapshot>` to `gradle.properties`.
- Change the root build to resolve version with this order:
  1. `releaseVersion`
  2. `projectVersion`
  3. fallback snapshot only as a defensive default

This creates a single version source that the workflow can safely edit.

### Release workflow bump

- Keep the existing publish step unchanged for Maven Central.
- After publish succeeds, run a second job on the default branch.
- Parse the released semantic version `X.Y.Z`.
- Compute the next minor snapshot as `X.(Y+1).0-SNAPSHOT`.
- Update `gradle.properties`.
- Commit with the GitHub Actions bot identity.
- Push back to the repository default branch.

### Safety rules

- Only run the bump job after publishing succeeds.
- Only accept stable semantic versions matching `^[0-9]+\\.[0-9]+\\.[0-9]+$`.
- Skip commit if the target snapshot is already present.
- Grant `contents: write` permission only because the workflow must push the version bump commit.

## Risks And Mitigations

- A malformed tag like `v1.2` should not produce a wrong snapshot.
  Mitigation: validate the version string and fail early.
- Workflow pushes back to `main`.
  Mitigation: write only `gradle.properties`, use deterministic commit message, and skip when there is no change.
