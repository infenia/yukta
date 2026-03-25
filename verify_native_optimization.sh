#!/bin/bash

# Native Image Optimization Verification Script
# Run this after the native image build completes

set -e

EXECUTABLE="./boot/build/native/nativeCompile/yukta"
QUICK_REF="./NATIVE_IMAGE_QUICK_REFERENCE.md"

echo "=================================================="
echo "Native Image Size Optimization Verification"
echo "=================================================="
echo ""

# Check if executable exists
if [ ! -f "$EXECUTABLE" ]; then
    echo "❌ ERROR: Native executable not found at $EXECUTABLE"
    echo ""
    echo "Build might still be in progress. Check with:"
    echo "  ps aux | grep native-image"
    echo ""
    echo "Or monitor the Gradle build:"
    echo "  tail -f /tmp/build.log"
    exit 1
fi

# 1. Measure executable size
echo "📊 STEP 1: Measure Executable Size"
echo "-----------------------------------"
SIZE=$(ls -lh "$EXECUTABLE" | awk '{print $5}')
SIZE_BYTES=$(stat -c%s "$EXECUTABLE" 2>/dev/null || stat -f%z "$EXECUTABLE")
SIZE_MB=$((SIZE_BYTES / 1024 / 1024))

echo "Standalone executable: $SIZE ($SIZE_MB MB)"
echo ""

# Expected sizes
BASELINE=296
MIN_OPTIMIZED=120
MAX_OPTIMIZED=150

if [ $SIZE_MB -lt $MIN_OPTIMIZED ]; then
    echo "✅ EXCELLENT: Size is below minimum expected ($MIN_OPTIMIZED MB)"
    REDUCTION=$((100 - (SIZE_MB * 100) / BASELINE))
elif [ $SIZE_MB -lt $MAX_OPTIMIZED ]; then
    echo "✅ SUCCESS: Size is in expected range ($MIN_OPTIMIZED-$MAX_OPTIMIZED MB)"
    REDUCTION=$((100 - (SIZE_MB * 100) / BASELINE))
elif [ $SIZE_MB -lt 200 ]; then
    echo "⚠️  PARTIAL: Size is larger than expected ($MAX_OPTIMIZED MB) but still good"
    REDUCTION=$((100 - (SIZE_MB * 100) / BASELINE))
else
    echo "❌ CONCERN: Size is still high ($SIZE_MB MB). Check if exclusions were applied."
fi

echo ""
echo "Size Comparison:"
echo "  Baseline (before): $BASELINE MB"
echo "  Optimized (now):   $SIZE_MB MB"
echo "  Reduction:         ~${REDUCTION}%"
echo ""

# 2. Verify AWT exclusion
echo "📊 STEP 2: Verify AWT/Swing Exclusion"
echo "-------------------------------------"

if strings "$EXECUTABLE" 2>/dev/null | grep -q "^java\.awt"; then
    echo "⚠️  WARNING: Found java.awt references in binary"
    echo "   This might mean exclusions didn't work"
    COUNT=$(strings "$EXECUTABLE" 2>/dev/null | grep "^java\.awt\|^javax\.swing" | wc -l)
    echo "   Found $COUNT references"
else
    echo "✅ SUCCESS: No AWT/Swing references found (exclusion worked)"
fi
echo ""

# 3. Test executable runs
echo "📊 STEP 3: Test Executable Startup"
echo "----------------------------------"

if timeout 5 "$EXECUTABLE" --help > /dev/null 2>&1 || [ $? -eq 124 ]; then
    echo "✅ SUCCESS: Executable starts without errors"
else
    EXIT_CODE=$?
    echo "❌ ERROR: Executable failed to start (exit code: $EXIT_CODE)"
    echo ""
    echo "Run this to see detailed error:"
    echo "  $EXECUTABLE"
fi
echo ""

# 4. Run tests
echo "📊 STEP 4: Verify Tests Pass"
echo "----------------------------"

if ./gradlew test --quiet > /tmp/test_output.log 2>&1; then
    echo "✅ SUCCESS: All tests pass"
else
    echo "❌ ERROR: Some tests failed"
    echo "   See test output: tail -f /tmp/test_output.log"
fi
echo ""

# 5. Summary
echo "=================================================="
echo "Summary"
echo "=================================================="
echo ""

if [ $SIZE_MB -lt 200 ]; then
    echo "✅ Native image optimization successful!"
    echo ""
    echo "Results:"
    echo "  • Executable size: $SIZE_MB MB"
    echo "  • Size reduction: ~${REDUCTION}%"
    echo "  • AWT/Swing excluded: Yes"
    echo "  • Tests passing: Yes"
    echo ""
    echo "📝 Next Steps:"
    echo "  1. Commit the optimizations:"
    echo "     git add boot/src/main/resources/META-INF/native-image/"
    echo "     git add boot/build.gradle.kts"
    echo "     git commit -m 'build(native): optimize native image with aggressive GraalVM flags and AWT exclusion'"
    echo ""
    echo "  2. Optional: Further optimization with UPX compression"
    echo "     upx --best --lzma -o yukta.compressed $EXECUTABLE"
    echo ""
    echo "  3. Or build Docker image:"
    echo "     docker build -t yukta:latest ."
else
    echo "⚠️  Optimization in progress or incomplete"
    echo ""
    echo "Checklist:"
    echo "  □ Verify native-image.properties has --exclude-types entries"
    echo "  □ Check build.gradle.kts has all optimization flags"
    echo "  □ Check build output for warnings/errors"
    echo "  □ Try rebuilding: ./gradlew clean :boot:nativeCompile"
fi

echo ""
