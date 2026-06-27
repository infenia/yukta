# Dependency Locking & Spring Boot 4.1.0 Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add reproducible dependency locking to all subprojects and upgrade Spring Boot to 4.1.0 with compatible Spring AI version.

**Architecture:** 
1. Add `dependencyLocking { lockAllConfigurations() }` to root `build.gradle.kts` in `allprojects {}` block (applies to all modules except `build-logic`)
2. Upgrade Spring Boot from 4.0.3 to 4.1.0 in `gradle/libs.versions.toml`
3. Check Spring AI 2.0.0-M2 compatibility with Boot 4.1.0 and update to compatible version if needed
4. Generate `gradle.lockfile` in each subproject using `./gradlew dependencies --write-locks --no-configuration-cache`
5. Verify build succeeds with `./gradlew clean build`
6. Commit all changes including lock files

**Tech Stack:** 
- Gradle 9.6.0 (version locking feature)
- Spring Boot 4.1.0 (target version)
- Spring AI 2.0.0-M2+ (compatibility check required)

## Global Constraints

- Lock all configurations (compileClasspath, runtimeClasspath, testCompileClasspath, etc.)
- Apply locking to all main subprojects; explicitly exclude `build-logic` composite build
- Use `--no-configuration-cache` flag when running `./gradlew dependencies --write-locks` (configuration cache is enabled in gradle.properties)
- All generated `gradle.lockfile` files must be committed to version control
- No code changes required; configuration and metadata updates only

---

### Task 1: Check Spring AI Compatibility with Spring Boot 4.1.0

**Files:**
- Reference: `gradle/libs.versions.toml` (read-only for this task)

**Interfaces:**
- Produces: Confirmed compatible Spring AI version string (e.g., "2.0.0-M3", "2.0.0-M4", or "2.0.0")

**Steps:**

- [ ] **Step 1: Check Spring AI 2.0.0-M2 release notes**

Run the following to see what Spring Boot versions are supported by current Spring AI:
```bash
# Check Maven Central for Spring AI 2.0.0-M2 and its Spring Boot compatibility
curl -s "https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-starter-mcp-server-webflux/maven-metadata.xml" | grep -oP '<version>[^<]*</version>' | head -20
```

Expected output: Shows available Spring AI versions in 2.0.0 line (M1 through final release)

- [ ] **Step 2: Determine compatible Spring AI version**

Based on the available versions and Spring Boot 4.1.0 release date (June 2026), determine the correct Spring AI version:
- If `2.0.0-M4` or later exists → use that (later milestones are more compatible with newer Boot)
- If only `2.0.0-M3` exists → use that
- If final `2.0.0` is released → use that
- If only `2.0.0-M2` exists or no later version → keep current `2.0.0-M2`

Document the version you'll use for Task 3.

---

### Task 2: Add Dependency Locking Configuration to Root Build File

**Files:**
- Modify: `build.gradle.kts` (root) — `allprojects {}` block

**Interfaces:**
- Produces: Gradle dependency locking enabled for all subprojects

**Steps:**

- [ ] **Step 1: Read root build.gradle.kts**

Check the current structure:
```bash
cat build.gradle.kts
```

Expected structure:
```kotlin
plugins {
    base
    alias(libs.plugins.cyclonedx)
}

allprojects {
    group = "com.infenia.yukta"
    version = "0.0.1-SNAPSHOT"
}
```

- [ ] **Step 2: Add dependency locking to allprojects block**

Edit `build.gradle.kts` to add `dependencyLocking { lockAllConfigurations() }` inside the `allprojects {}` block:

```kotlin
plugins {
    base
    alias(libs.plugins.cyclonedx)
}

allprojects {
    group = "com.infenia.yukta"
    version = "0.0.1-SNAPSHOT"

    dependencyLocking {
        lockAllConfigurations()
    }
}
```

- [ ] **Step 3: Verify the change**

Run:
```bash
cat build.gradle.kts | grep -A 3 "dependencyLocking"
```

Expected output:
```
dependencyLocking {
    lockAllConfigurations()
}
```

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit --no-gpg-sign -m "feat: enable dependency locking for all configurations"
```

Expected: Commit succeeds with 1 file changed

---

### Task 3: Upgrade Spring Boot and Spring AI Versions

**Files:**
- Modify: `gradle/libs.versions.toml` — `[versions]` section

**Interfaces:**
- Consumes: Compatible Spring AI version from Task 1
- Produces: Updated version strings in libs.versions.toml

**Steps:**

- [ ] **Step 1: Read current versions**

```bash
grep -E "springBoot|springAi" gradle/libs.versions.toml | head -5
```

Expected output:
```
springBoot = "4.0.3"
springDependencyManagement = "1.1.7"
...
springAi = "2.0.0-M2"
```

- [ ] **Step 2: Update Spring Boot version to 4.1.0**

Edit `gradle/libs.versions.toml` and change line 2:

From:
```toml
springBoot = "4.0.3"
```

To:
```toml
springBoot = "4.1.0"
```

- [ ] **Step 3: Update Spring AI version**

Based on the version determined in Task 1, update the `springAi` line. 

If Task 1 determined `2.0.0-M4` is available:
```toml
springAi = "2.0.0-M4"
```

Or if `2.0.0` final is available:
```toml
springAi = "2.0.0"
```

Or if keeping `2.0.0-M2`:
```toml
springAi = "2.0.0-M2"
```

- [ ] **Step 4: Verify changes**

```bash
grep -E "springBoot|springAi" gradle/libs.versions.toml | head -5
```

Expected output (example with M4):
```
springBoot = "4.1.0"
springDependencyManagement = "1.1.7"
...
springAi = "2.0.0-M4"
```

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml
git commit --no-gpg-sign -m "build(deps): upgrade Spring Boot to 4.1.0 and Spring AI to compatible version"
```

Expected: Commit succeeds with 1 file changed

---

### Task 4: Generate Dependency Lock Files

**Files:**
- Create: `gradle.lockfile` in each subproject directory
- Reference subprojects: core, web, boot, mcp, ui, cli, cli-boot, messaging, plugin-api, plugins/processors/*, plugins/triggers/*, plugins/terminals/*

**Interfaces:**
- Consumes: Updated `build.gradle.kts` (Task 2) and `gradle/libs.versions.toml` (Task 3)
- Produces: Multiple `gradle.lockfile` files (one per subproject), committed to git

**Steps:**

- [ ] **Step 1: Clean build artifacts to ensure fresh resolution**

```bash
./gradlew clean
```

Expected: Build cache cleared, no errors

- [ ] **Step 2: Generate lock files with configuration cache disabled**

```bash
./gradlew dependencies --write-locks --no-configuration-cache
```

Expected output: Long build with dependency resolution for all modules. Command completes successfully.
Expected time: 2-5 minutes depending on network and cache.

The `--no-configuration-cache` flag is necessary because `gradle.properties` has `org.gradle.configuration-cache=true`, which is incompatible with `--write-locks`.

- [ ] **Step 3: Verify lock files were created**

Check that lock files exist in main subproject directories (not in build-logic):

```bash
find . -name "gradle.lockfile" -not -path "./build-logic/*" | head -15
```

Expected output (at least these):
```
./core/gradle.lockfile
./web/gradle.lockfile
./boot/gradle.lockfile
./mcp/gradle.lockfile
./ui/gradle.lockfile
./cli/gradle.lockfile
./cli-boot/gradle.lockfile
./messaging/gradle.lockfile
./plugin-api/gradle.lockfile
./plugins/processors/process-executor/gradle.lockfile
./plugins/processors/internal/internal-core/gradle.lockfile
./plugins/triggers/api-trigger/gradle.lockfile
./plugins/triggers/constant-source/gradle.lockfile
./plugins/triggers/auto-trigger/gradle.lockfile
./plugins/terminals/console-terminal/gradle.lockfile
```

- [ ] **Step 4: Verify build-logic does NOT have a lock file**

```bash
ls -la build-logic/gradle.lockfile 2>&1 || echo "Correctly absent"
```

Expected output:
```
Correctly absent
```

- [ ] **Step 5: Stage all lock files**

```bash
git add "*/gradle.lockfile" "*/*/gradle.lockfile" "*/*/*/gradle.lockfile"
```

- [ ] **Step 6: Commit lock files**

```bash
git commit --no-gpg-sign -m "feat: add dependency lock files for reproducible builds"
```

Expected: Commit succeeds with multiple files changed (one `gradle.lockfile` per subproject)

---

### Task 5: Verify Build and Quality Gates

**Files:**
- All source files (read-only — verification task)

**Interfaces:**
- Consumes: All changes from Tasks 2, 3, and 4
- Produces: Verified working build with all quality gates passing

**Steps:**

- [ ] **Step 1: Run clean build**

```bash
./gradlew clean build
```

Expected: Build completes with "BUILD SUCCESSFUL". All modules compile, all tests pass.
Expected time: 5-10 minutes

If this fails, check:
- Does Spring Boot 4.1.0 exist? (May need to revert if pre-release issues)
- Is Spring AI version compatible? (May need adjustment)
- Do any tests rely on deprecated Boot 4.0.x APIs? (Unlikely but possible)

- [ ] **Step 2: Run format check**

```bash
./gradlew spotlessApply
```

Expected: No changes (or only whitespace if formatter has different rules). Command completes successfully.

- [ ] **Step 3: Run quality gates**

```bash
./gradlew check
```

Expected: All quality checks pass:
- Checkstyle (code style)
- PMD (code quality rules)
- SpotBugs (bug detection)
- JaCoCo (code coverage)
- Tests (all modules)

Expected output ends with:
```
BUILD SUCCESSFUL
```

- [ ] **Step 4: Verify no uncommitted changes from locking**

```bash
git status
```

Expected output:
```
On branch feat/add-dependency-locking
nothing to commit, working tree clean
```

If there are uncommitted `gradle.lockfile` changes, stage and commit them:
```bash
git add "*/gradle.lockfile" "*/*/gradle.lockfile" "*/*/*/gradle.lockfile"
git commit --no-gpg-sign -m "chore: update lock files after quality gate verification"
```

---

### Task 6: Final Verification Checklist and Summary

**Files:**
- Reference: All modifications from Tasks 2, 3, 4, 5 (read-only verification)

**Interfaces:**
- Consumes: All completed tasks
- Produces: Confirmed implementation matches spec requirements

**Steps:**

- [ ] **Step 1: Verify build.gradle.kts has locking config**

```bash
grep -A 2 "dependencyLocking" build.gradle.kts
```

Expected output:
```
dependencyLocking {
    lockAllConfigurations()
}
```

- [ ] **Step 2: Verify Spring Boot version is 4.1.0**

```bash
grep "springBoot = " gradle/libs.versions.toml
```

Expected output:
```
springBoot = "4.1.0"
```

- [ ] **Step 3: Verify Spring AI was updated**

```bash
grep "springAi = " gradle/libs.versions.toml
```

Expected output (example):
```
springAi = "2.0.0-M4"
```
(or whatever compatible version was determined in Task 1)

- [ ] **Step 4: Count lock files**

```bash
find . -name "gradle.lockfile" -not -path "./build-logic/*" | wc -l
```

Expected output: A number >= 15 (representing all main subprojects)

- [ ] **Step 5: Confirm no lock files in build-logic**

```bash
find ./build-logic -name "gradle.lockfile" 2>/dev/null || echo "None found (correct)"
```

Expected output:
```
None found (correct)
```

- [ ] **Step 6: Review git log to see all commits**

```bash
git log --oneline -6
```

Expected output (newest first):
```
<commit-hash> feat: add dependency lock files for reproducible builds
<commit-hash> build(deps): upgrade Spring Boot to 4.1.0 and Spring AI to compatible version
<commit-hash> feat: enable dependency locking for all configurations
<previous commits...>
```

- [ ] **Step 7: Confirm working tree is clean**

```bash
git status
```

Expected output:
```
On branch feat/add-dependency-locking
nothing to commit, working tree clean
```

All tasks complete. Implementation ready for PR or merge.

---

## Self-Review Against Spec

**Spec Coverage:**
- ✅ Section 1 (Dependency Locking): Task 2 adds config; Task 4 generates lock files; excludes build-logic
- ✅ Section 2 (Spring Boot Upgrade): Task 3 updates version to 4.1.0
- ✅ Spring AI Compatibility: Task 1 checks compatibility; Task 3 updates version
- ✅ Section 3 (Implementation Steps): All tasks correspond to spec steps
- ✅ Section 4 (Verification Checklist): Task 5 runs build verification; Task 6 final checklist

**No Placeholders:** All steps have concrete commands and expected outputs. No "TBD" or vague directives.

**Type Consistency:** Version strings are exact (4.1.0, Spring AI versions confirmed in Task 1). Lock file paths use exact glob patterns.

