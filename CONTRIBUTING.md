# Contributing

Thanks for your interest in `hexregex`. Bug reports, puzzle-quality reports and
pull requests are all welcome. Please open an
[issue](https://github.com/CuSO4Deposit/regex-crossword/issues) to discuss a
change before starting anything large.

## Reference implementation (`hexregex`)

`hexregex/` is the Python prototype used to design and test the algorithms; the
Android app is the product. It requires Python ≥ 3.8 and no third-party packages
and is not published.

```bash
python3 -m unittest discover -s tests -v
```

Style: standard library only. Keep the generator deterministic — all randomness
must come from the single `random.Random(seed)` passed through `GenConfig`; do
not use `time`, `os.urandom`, set iteration order, or any other entropy source.

The generator's output is a compatibility surface (puzzle JSON, and the level
ids the Android app derives from seeds), so a change that alters generated
clues for an existing seed should bump the Android `GENERATOR_VERSION` (see
[`android/README.md`](android/README.md)).

## Android app

The app lives in [`android/`](android/README.md). It needs JDK 17 and an
Android SDK; the pure-JVM `:engine` module does **not** need the SDK.

```bash
cd android
./gradlew -p engine test          # engine unit tests, no Android SDK needed
./gradlew :app:assembleDebug      # debug APK
```

`android/engine` is a faithful port of the Python geometry, generator and
solver, kept byte-for-byte compatible through parity tests. If you change the
Python generator, mirror it in Kotlin and re-run both suites; the parity tests
compare emitted JSON byte for byte.

Release signing reads the gitignored `android/keystore.properties` (copy
`android/keystore.properties.example`). Never commit a keystore or its
passwords. To build a release locally and fail if signing is missing, pass
`-PrequireSigning`:

```bash
./gradlew :app:assembleRelease -PrequireSigning
```

## Commits and pull requests

- Keep commits focused; explain the "why" in the message.
- Run the Python tests and, for engine/app changes, `./gradlew -p engine test`
  before opening a PR.
- Update `CHANGELOG.md` for user-visible changes.
- Do not add Nix-specific files (`flake.nix`, `shell.nix`) or make the shared
  build depend on Nix; collaborators use plain toolchains.
