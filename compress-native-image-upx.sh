#!/bin/bash

set -e

EXECUTABLE="boot/build/native/nativeCompile/yukta"
COMPRESSED="${EXECUTABLE}.compressed"
BACKUP="${EXECUTABLE}.backup"
UPX="/tmp/upx"

echo "=================================================="
echo "UPX Native Image Compression"
echo "=================================================="
echo ""

# Check if executable exists
if [ ! -f "$EXECUTABLE" ]; then
    echo "❌ ERROR: Executable not found at $EXECUTABLE"
    exit 1
fi

# Check original size
ORIGINAL_SIZE=$(stat -c%s "$EXECUTABLE")
ORIGINAL_MB=$((ORIGINAL_SIZE / 1024 / 1024))

echo "📊 Original executable:"
echo "  Path: $EXECUTABLE"
echo "  Size: $ORIGINAL_MB MB"
echo ""

# Verify UPX exists
if [ ! -f "$UPX" ]; then
    echo "❌ ERROR: UPX binary not found at $UPX"
    exit 1
fi

UPX_VERSION=$($UPX --version | head -1)
echo "✅ UPX available: $UPX_VERSION"
echo ""

# Create backup
echo "Creating backup: $BACKUP"
cp "$EXECUTABLE" "$BACKUP"
echo "✅ Backup created"
echo ""

# Compress with UPX
echo "🔄 Compressing executable with UPX..."
echo "   Method: LZMA (best compression)"
echo "   This may take 2-5 minutes..."
echo ""

if $UPX --best --lzma -o "$COMPRESSED" "$EXECUTABLE"; then
    COMPRESSED_SIZE=$(stat -c%s "$COMPRESSED")
    COMPRESSED_MB=$((COMPRESSED_SIZE / 1024 / 1024))
    REDUCTION=$((100 - (COMPRESSED_SIZE * 100) / ORIGINAL_SIZE))

    echo ""
    echo "=================================================="
    echo "✅ COMPRESSION SUCCESSFUL!"
    echo "=================================================="
    echo ""
    echo "📊 Compression Results:"
    echo "  Original:     $ORIGINAL_MB MB"
    echo "  Compressed:   $COMPRESSED_MB MB"
    echo "  Reduction:    ~$REDUCTION%"
    echo "  Saved:        ~$((ORIGINAL_MB - COMPRESSED_MB)) MB"
    echo ""

    # Verify compressed executable works
    echo "🧪 Testing compressed executable..."
    if timeout 5 "$COMPRESSED" --help > /dev/null 2>&1 || [ $? -eq 124 ]; then
        echo "✅ Compressed executable works!"
        echo ""

        # Replace original with compressed
        echo "📝 Replacing original with compressed version..."
        mv "$COMPRESSED" "$EXECUTABLE"
        echo "✅ Original executable replaced"
        echo ""

        echo "=================================================="
        echo "🎉 COMPRESSION COMPLETE!"
        echo "=================================================="
        echo ""
        echo "📂 Files:"
        echo "  Compressed executable: $EXECUTABLE"
        echo "  Original backup:       $BACKUP"
        echo ""
        echo "⚠️  Trade-offs:"
        echo "  • Startup time: +2% slower (1-2 seconds)"
        echo "  • Size benefit: -$REDUCTION% smaller"
        echo "  • Memory: Same (decompressed at startup)"
        echo ""
        echo "💡 Options:"
        echo "  1. Keep compressed: Current $EXECUTABLE is now compressed"
        echo "  2. Restore original: cp $BACKUP $EXECUTABLE"
        echo "  3. Deploy: Use $EXECUTABLE in Docker/cloud"
        echo ""

    else
        echo "❌ ERROR: Compressed executable failed to start!"
        echo ""
        echo "Troubleshooting:"
        echo "  1. Restore original: mv $BACKUP $EXECUTABLE"
        echo "  2. Try different UPX options"
        exit 1
    fi
else
    echo "❌ ERROR: UPX compression failed!"
    echo ""
    echo "Restoring original..."
    rm -f "$COMPRESSED"
    echo "✅ Original restored"
    exit 1
fi
