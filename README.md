# ForgeBuild AI

A lightweight Android control center for GitHub Actions Android builds.

## What it does

- Stores a GitHub token encrypted with the Android Keystore.
- Tests GitHub authentication.
- Dispatches a workflow on any selected branch with a build type input (Debug APK / Release APK / Release AAB / Auto).
- Polls the workflow run and jobs (up to 60 minutes) and shows job status and logs.
- Cancels a running workflow, retries failed jobs, opens the run in GitHub.
- Downloads the built artifact (a .zip containing the APK/AAB) to Downloads.

## Build the APK

Push this repository to GitHub, then open:

Actions → ForgeBuild AI - Android → Run workflow

The APK is uploaded as a GitHub Actions artifact (unzip it, install the APK).

## Using it on another repository

Copy `.github/workflows/build.yml` into that repository (it must exist on the branch you build).
If the workflow has no `build_type` input, the app automatically dispatches without inputs.

## GitHub token

Fine-grained token: repository access to the target repo, permission **Actions: Read and write**.
Classic token: scopes `repo` and `workflow`.

## Notes

- Release APKs without a signing config are signed with a throwaway key by the workflow so they are installable. Use real signing for distribution.
- Requires Android 10 (API 29) or newer.
