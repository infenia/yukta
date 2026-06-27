# Dependency Locking & Spring Boot 4.1.0 Upgrade

**Date:** 2026-06-27  
**Scope:** Add dependency locking to all subprojects and upgrade Spring Boot from 4.0.3 to 4.1.0

## Overview

This spec covers two related improvements:
1. **Dependency Locking:** Lock all transitive dependencies across all subprojects to ensure reproducible builds and prevent supply-chain surprises.
2. **Spring Boot Upgrade:** Upgrade from 4.0.3 to 4.1.0 to gain the latest performance and compatibility improvements.

## 1. Dependency Locking

### Scope
- Apply locking to **all subprojects in the main build** (core, web, boot, mcp, ui, cli, messaging, plugin-api, plugins/*, etc.)
- **Exclude** `build-logic` — it's a composite build for conventions and tooling, not a deployable artifact
- Lock **all configurations** (compileClasspath, runtimeClasspath, testCompileClasspath, etc.) for maximum reproducibility

### Configuration
Add to root `build.gradle.kts` in the `allprojects {}` block:
```kotlin
dependencyLocking {
    lockAllConfigurations()
}
```

### Lock File Generation
Run:
```bash
./gradlew dependencies --write-locks --no-configuration-cache
```

The `--no-configuration-cache` flag is required because:
- `gradle.properties` has `org.gradle.configuration-cache=true`
- Configuration cache and `--write-locks` are incompatible
- This is a one-time operation during lock generation only

Result: `gradle.lockfile` will be generated in each subproject's root directory.

### Lock File Commit
All generated `gradle.lockfile` files must be committed to version control. They define the exact transitive dependency tree and become part of the reproducible build contract.

## 2. Spring Boot Upgrade

### Version Changes
Update in `gradle/libs.versions.toml`:
- `springBoot = "4.0.3"` → `springBoot = "4.1.0"`

### Spring AI Compatibility
Spring AI 2.0.0-M2 (current) may not align with Spring Boot 4.1.0. 
- Check Spring AI release notes and Maven Central for compatible version
- Expected: likely upgrade to `springAi = "2.0.0-M3"`, `2.0.0-M4"`, or final `2.0.0` if released
- Update `springAi` version in `gradle/libs.versions.toml` accordingly

### Compatibility Notes
- Spring Boot 4.1.0 is a minor release from the 4.x series (same Spring Framework base)
- No breaking changes expected for existing dependencies (Jackson, Lombok, MapStruct, Reactor, etc.)
- All existing code should remain compatible without modifications

## 3. Implementation Steps

1. **Modify `build.gradle.kts`** — add dependency locking config to `allprojects {}`
2. **Update `gradle/libs.versions.toml`** — bump Spring Boot and Spring AI versions
3. **Generate lock files** — run `./gradlew dependencies --write-locks --no-configuration-cache`
4. **Verify build** — run `./gradlew clean build` to ensure all modules compile and tests pass
5. **Commit** — commit all changes with conventional commit format

## 4. Verification Checklist

- [ ] `build.gradle.kts` contains `dependencyLocking { lockAllConfigurations() }`
- [ ] `gradle.lockfile` exists in all main subproject directories (check at least core, boot, web)
- [ ] `gradle.lockfile` is NOT in `build-logic/`
- [ ] `gradle/libs.versions.toml` shows `springBoot = "4.1.0"`
- [ ] `gradle/libs.versions.toml` shows updated Spring AI version
- [ ] `./gradlew clean build` completes successfully with no errors
- [ ] All tests pass
- [ ] Quality gates (spotlessApply, checkstyle, pmd, spotbugs) pass

## 5. Future Maintenance

### Updating Dependencies
When updating dependencies:
1. Modify the version in `gradle/libs.versions.toml` or use `./gradlew dependencyUpdates`
2. Run `./gradlew dependencies --write-locks --no-configuration-cache` to regenerate locks
3. Commit the updated `gradle.lockfile` files

### Reviewing Lock Files
Lock files should be reviewed in PRs to:
- Detect unexpected transitive dependency upgrades
- Spot potential security issues in dependencies
- Understand the full dependency tree impact

