## What and why

## How it was tested

## Checklist

- [ ] `Scripts/lint.sh` passes
- [ ] C tests (`Core/Tests/run_tests.sh`) and Swift tests pass
- [ ] Android: `./gradlew build` and `./gradlew ktlintCheck` pass in `Android/` (if Android changed)
- [ ] New or changed public API has DocC or KDoc comments; renames and removals are listed in `CHANGELOG.md`
- [ ] The public API baselines are updated if the API changed (`Apple/Scripts/swift-api.sh dump` on macOS, `./gradlew updateKotlinAbi` in `Android/`)
- [ ] A change on one platform has its counterpart on the other, or the pull request says why not
- [ ] `CHANGELOG.md` has an entry under "Unreleased"
- [ ] Changes to baresip or libre are patches in `Core/Patches/` with a row in `Core/Patches/README.md`
- [ ] No credentials, real host names, IP addresses or phone numbers in code, tests or logs
