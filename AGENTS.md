# Working on Tinyscreen

- Work on `main` and push it. There are no other branches.
- Every push to `main` that changes the app (`app/`, Gradle files) builds a signed APK and publishes
  it as a GitHub release, which Obtainium on the phone installs as an update
  (`.github/workflows/release.yml`).
- Don't bump `version_name`/`version_code`, create tags or run `gh release` by hand. CI assigns the
  next `1.0.N`.
- Release notes are the commit subjects since the last release, so write subjects a user can read.
- Never change the release signing key: Android only installs updates signed with the same certificate.
  The keystore lives outside the repo in `~/.android/tinyscreen/` (`key.properties` here is a symlink to it)
  and in the repo's Actions secrets.
- Check before pushing: `./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease`
  (CI fails the release on a lint error that isn't in `app/lint-baseline.xml`)
