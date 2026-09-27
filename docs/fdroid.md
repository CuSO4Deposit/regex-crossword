# Publishing on F-Droid

The app is F-Droid-friendly: MIT, no tracking, no ads, no network permission,
and no proprietary dependencies. It can go either to the **official** F-Droid
repository or to a **self-hosted** repository you run yourself. You can do both,
but note the signing difference below.

## Signing difference (read first)

- **Official F-Droid** builds the app from source and signs it with
  **F-Droid's** key. Users who already installed your own signed APK **cannot
  upgrade** to the F-Droid build (and vice versa) — Android rejects a different
  signature. Switching sources means uninstall + reinstall.
- **Self-hosted** uses **your** key (`android/release.jks`), so it is a drop-in
  upgrade for anyone on your existing build.

Pick the one whose key you want users to end up on.

## Metadata

Store descriptions, screenshots, the icon and per-`versionCode` changelogs live
in the repo under `fastlane/metadata/android/en-US/` (already committed).
`fdroidserver` reads them automatically.

The `fdroiddata` entry (for the official repo, or as
`fdroid/metadata/<appid>.yml` for a self-hosted repo):

```yaml
Categories:
  - Games
License: MIT
AuthorName: CuSO4Deposit
WebSite: https://github.com/CuSO4Deposit/regex-crossword
SourceCode: https://github.com/CuSO4Deposit/regex-crossword
IssueTracker: https://github.com/CuSO4Deposit/regex-crossword/issues
Changelog: https://github.com/CuSO4Deposit/regex-crossword/blob/HEAD/CHANGELOG.md

AutoName: Regex Crossword
Summary: Offline regex-crossword puzzles, generated on your device

RepoType: git
Repo: https://github.com/CuSO4Deposit/regex-crossword

Builds:
  - versionName: 0.1.0
    versionCode: 1
    commit: v0.1.0
    subdir: android
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.1.0
CurrentVersionCode: 1
```

Notes:

- `commit: v0.1.0` requires the release tag to exist (see the repo root
  instructions). Use a commit hash if you have not tagged yet.
- `gradle: - yes` runs the release build; because `keystore.properties` is
  absent in the F-Droid build environment, the APK is produced unsigned and
  F-Droid signs it. (`-PrequireSigning` is intentionally not used.)
- `Summary`/descriptions/screenshots come from `fastlane/`.

## Official F-Droid

1. Tag the release in the repository (`v0.1.0`) and make the repo public.
2. Fork [`fdroid/fdroiddata`](https://gitlab.com/fdroid/fdroiddata) and add
   `metadata/io.github.cuso4deposit.regexcrossword.yml` (contents above).
3. Open a merge request. F-Droid reviews the recipe, test-builds the app, and
   (once merged) publishes it with the next index update.
4. Expect review to take days to a few weeks; keep `versionCode` increasing per
   release and update `CurrentVersion`/`CurrentVersionCode`.

## Self-hosted repository

1. Install `fdroidserver` and `fdroid init` in an empty directory.
2. Put your keystore in place and configure `config.yml` (`keystore`,
   `repo_url`, `repo_name`, `repo_icon`).
3. Add the metadata above as `metadata/io.github.cuso4deposit.regexcrossword.yml`.
4. `fdroid build io.github.cuso4deposit.regexcrossword`, then `fdroid update`
   to generate the index, and publish the resulting directory (it can be served
   as a plain static site / GitLab Pages).
5. Add the repo URL in the F-Droid client under _Settings → Repositories_.

Because this repo is signed with your key, existing installs upgrade normally.
