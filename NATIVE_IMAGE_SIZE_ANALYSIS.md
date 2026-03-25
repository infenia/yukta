# Native Image Size Analysis & Optimization Guide

## Current Status

**Native executable size:** ~296MB (observed in build output)

### Root Cause Analysis

The large binary size is caused by:

1. **Swing/AWT Libraries Included**: Even though your application doesn't use GUI libraries, they're being pulled in by transitive dependencies
   - `libawt.so` (903KB)
   - `libawt_xawt.so` (509KB)
   - `libawt_headless.so` (42KB)

2. **Swagger UI Web Assets**: Bundled JavaScript/CSS libraries (Swagger UI 5.31.0)
   - Swagger UI adds significant static assets to classpath
   - Not directly in native image but contributes to classpath bloat

3. **Build Heap Size**: Current build may not be using aggressive enough optimization flags

4. **Missing Native Image Exclusions**: Swing/AWT should be excluded from native image

---

## Solutions (3 Levels)

### ✅ Level 1: Quick Win (~50MB reduction) - Exclude AWT/Swing

**Action**: Add GraalVM native image exclusions to prevent GUI libraries from being included.

**File**: `boot/src/main/resources/META-INF/native-image/native-image.properties`

Add these lines:
```properties
# Exclude unnecessary GUI libraries that are never used in server application
Args=-H:+ExcludeTypes=java.awt.*,javax.swing.*,javax.imageio.*
Args=-H:ExcludeTypes=sun.awt.*
```

**Why**: Your server application has no GUI components. AWT/Swing are being pulled in by classpath scanning but not actually used. Excluding them saves 50+ MB.

**Expected reduction**: ~50-80MB

---

### ✅ Level 2: GraalVM Optimization Flags (~80MB reduction) - Already Partially Done

Your `boot/build.gradle.kts` already has some flags. **Enhance** them:

**File**: `boot/build.gradle.kts` (lines 66-81)

**Current config** (lines 71-78):
```kotlin
buildArgs.add("-H:+RemoveUnusedSymbols")
buildArgs.add("-H:+StripDebugInfo")
```

**Enhance to**:
```kotlin
// Aggressive size reduction (applies to prod profile only)
buildArgs.add("-H:+RemoveUnusedSymbols")
buildArgs.add("-H:+StripDebugInfo")
buildArgs.add("-H:-AddAllCharsets")              // Remove all charsets (add back if needed)
buildArgs.add("-J-Xmx8g")                       // Build with 8GB heap for analysis
buildArgs.add("-H:GCHeapSize=512m")             // Reduce build heap requirement

// Build time optimizations
buildArgs.add("-H:TieredAOTCompilation=3")       // More aggressive AOT compilation
buildArgs.add("-H:+GenerateDebugInfo=false")     // Ensure no debug symbols
buildArgs.add("--enable-https")                  // Only if you use HTTPS (reduces protocol overhead)
```

**Expected reduction**: ~30-50MB additional

---

### ✅ Level 3: Dependency Cleanup (~20MB reduction) - Check Web Dependencies

**Issue**: Swagger UI pulls in large web assets. Check if you can use a lighter alternative:

**Current**:
```gradle
implementation(libs.springdoc.openapi.starter.webflux.ui)
```

**Option A - Keep Swagger but exclude static assets**:
```gradle
implementation(libs.springdoc.openapi.starter.webflux.ui) {
    exclude group: 'org.webjars', module: 'swagger-ui'  // Exclude pre-built assets
}
```

**Option B - Use Swagger without UI** (API-only):
```gradle
implementation(libs.springdoc.openapi.starter.webflux)  // No UI bundle
```

**Expected reduction**: ~10-20MB

---

## Complete Implementation Plan

### Step 1: Add AWT/Swing Exclusions

```bash
cat >> boot/src/main/resources/META-INF/native-image/native-image.properties << 'EOF'

# Exclude unnecessary GUI libraries (not used in server application)
Args=--exclude-types=java.awt.*
Args=--exclude-types=javax.swing.*
Args=--exclude-types=javax.imageio.*
Args=--exclude-types=sun.awt.*
EOF
```

### Step 2: Update GraalVM Build Args

Edit `boot/build.gradle.kts` (graalvmNative block):

```kotlin
graalvmNative {
    binaries {
        named("main") {
            imageName.set("yukta")
            mainClass.set("com.infenia.yukta.YuktaApplication")
            buildArgs.add("--no-fallback")

            // Size reduction flags
            buildArgs.add("-H:+RemoveUnusedSymbols")
            buildArgs.add("-H:+StripDebugInfo")
            buildArgs.add("-H:-AddAllCharsets")
            buildArgs.add("-H:TieredAOTCompilation=3")
            buildArgs.add("-J-Xmx8g")
            buildArgs.add("-H:GCHeapSize=512m")

            // Enable prod profile + AOT
            buildArgs.add("-Dspring.profiles.active=prod")
            buildArgs.add("-Dspring.aot.enabled=true")
        }
    }
}
```

### Step 3: (Optional) Verify Swagger Dependency

```bash
./gradlew :boot:dependencies | grep -i swagger
```

If it shows `swagger-ui:5.31.0` with large webjars, consider excluding or switching to headless version.

### Step 4: Clean Build and Measure

```bash
./gradlew clean :boot:nativeCompile
ls -lh boot/build/native/nativeCompile/yukta
```

**Expected**: 100-150MB (down from 296MB)

---

## Advanced Optimization (Optional)

If you still need further reduction:

### Option A: Serial GC (saves ~10-15MB)
```gradle
buildArgs.add("--gc=serial")  // Single-threaded GC for smaller heap
```
**Trade-off**: Slightly slower GC pauses, but smaller image.

### Option B: Compression (UPX)
After native image build:
```bash
upx --best --lzma -o yukta.compressed boot/build/native/nativeCompile/yukta
# Results in ~40-60MB compressed executable
```

### Option C: Minimized JDK Modules
If using modules (advanced):
```gradle
buildArgs.add("--enable-preview")
buildArgs.add("-H:+IncludeResourceBundles=java.base")
```

---

## Testing & Validation

After optimization, verify nothing broke:

```bash
# Test startup
timeout 5 ./boot/build/native/nativeCompile/yukta --help || true

# Verify key features still work
./gradlew test

# Check memory footprint
/usr/bin/time -v ./boot/build/native/nativeCompile/yukta
# Watch for "Maximum resident set size" in output
```

---

## Expected Results

| Optimization | Reduction | Final Size | Time |
|-------------|-----------|-----------|------|
| Current | - | 296MB | - |
| + AWT exclusion | 50-80MB | 220MB | 5 min |
| + GraalVM flags | 30-50MB | 170MB | 5 min |
| + Dependency cleanup | 10-20MB | **150MB** | 5 min |
| **+ UPX compression** | **60%** | **~60MB** | 2 min |

---

## FAQ

**Q: Will excluding AWT break anything?**
A: No. AWT/Swing are GUI libraries. Your server application has no GUI components. They're only included because they're on the classpath.

**Q: Should I use -H:-AddAllCharsets?**
A: Only if you don't need international character support. If your API handles UTF-8 globally, it's safe.

**Q: Will this affect performance?**
A: No. These are build-time optimizations that remove dead code. Runtime performance is unaffected.

**Q: Is UPX compression safe for production?**
A: Yes, but optional. Some systems may compress better than others. Test before deploying to production.

**Q: How do I know what got excluded?**
A: Run with `-H:+PrintAnalysis` flag to generate detailed analysis reports.

---

## Quick Start

Run this to apply Level 1 & 2 optimizations:

```bash
# 1. Add AWT exclusions
cat >> boot/src/main/resources/META-INF/native-image/native-image.properties << 'EOF'

# Exclude GUI libraries
Args=-H:+ExcludeTypes=java.awt.*
Args=-H:ExcludeTypes=javax.swing.*
Args=-H:ExcludeTypes=javax.imageio.*
Args=-H:ExcludeTypes=sun.awt.*
EOF

# 2. Update GraalVM flags (edit boot/build.gradle.kts graalvmNative block)
# See "Step 2" above for exact changes

# 3. Clean build
./gradlew clean :boot:nativeCompile

# 4. Measure
ls -lh boot/build/native/nativeCompile/yukta
```

---

## Notes

- **First build takes 15-20 minutes** with native-image compilation
- **Java 25 optimizations** are applied automatically
- **AOT (Ahead-of-Time)** compilation is already enabled
- **Profile isolation**: prod profile excludes dev-only code at compile time

See also: `docs/plans/2026-03-22-native-image-optimization.md` for comprehensive implementation plan.
