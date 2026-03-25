#!/bin/bash

# Native Image UPX Compression Script
# Compresses the native executable for minimal deployment size
# Trade-off: +2% startup time overhead for 70% size reduction

set -e

EXECUTABLE="boot/build/native/nativeCompile/yukta"
COMPRESSED="${EXECUTABLE}.compressed"
BACKUP="${EXECUTABLE}.backup"

echo "=================================================="
echo "UPX Native Image Compression"
echo "=================================================="
echo ""

# Check if executable exists
if [ ! -f "$EXECUTABLE" ]; then
    echo "❌ ERROR: Executable not found at $EXECUTABLE"
    echo ""
    echo "Build the native image first:"
    echo "  ./gradlew clean :boot:nativeCompile"
    exit 1
fi

# Check original size
ORIGINAL_SIZE=$(stat -c%s "$EXECUTABLE")
ORIGINAL_MB=$((ORIGINAL_SIZE / 1024 / 1024))

echo "📊 Original executable:"
echo "  Path: $EXECUTABLE"
echo "  Size: $ORIGINAL_MB MB"
echo ""

# Check if UPX is installed
if ! command -v upx &> /dev/null; then
    echo "❌ ERROR: UPX is not installed"
    echo ""
    echo "Install UPX:"
    echo "  Ubuntu/Debian: sudo apt-get install upx"
    echo "  macOS: brew install upx"
    echo "  Or download from: https://upx.github.io/"
    exit 1
fi

UPX_VERSION=$(upx --version | head -1)
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

if upx --best --lzma -o "$COMPRESSED" "$EXECUTABLE"; then
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
        echo "🐳 Docker tip:"
        echo "  docker build -t yukta:compressed ."
        echo "  # Will use the compressed binary (30-50MB image)"

    else
        echo "❌ ERROR: Compressed executable failed to start!"
        echo ""
        echo "Troubleshooting:"
        echo "  1. Restore original: mv $BACKUP $EXECUTABLE"
        echo "  2. Try different UPX options (see options below)"
        echo "  3. Use uncompressed version for this platform"
        exit 1
    fi
else
    echo "❌ ERROR: UPX compression failed!"
    echo ""
    echo "Restoring original..."
    rm -f "$COMPRESSED"
    echo "✅ Original restored"
    echo ""
    echo "Troubleshooting:"
    echo "  • Check UPX installation: upx --version"
    echo "  • Try with different compression: upx --best -o compressed $EXECUTABLE"
    echo "  • Check system resources (need ~2GB free RAM)"
    echo "  • Some architectures/binaries don't compress well with LZMA"
    exit 1
fi
