#!/usr/bin/env bash
# Сборка libtailscale для всех платформ.
#
# Использование (кросс-компиляция через Zig, все платформы):
#   nix-shell shell.nix --run "bash scripts/build-native.sh [linux-amd64|macos-amd64|macos-arm64|windows-amd64|all]"
#
# Или нативно на своей платформе (без Nix, без Zig):
#   bash scripts/build-native.sh linux-amd64    # на Linux
#   bash scripts/build-native.sh macos-arm64    # на Apple Silicon
#   bash scripts/build-native.sh windows-amd64  # на Windows (требует MinGW-w64)

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/../native"

TARGET="${1:-all}"
OUT="$SCRIPT_DIR/../src/main/resources/natives"

build_linux_amd64() {
    echo "==> linux-amd64"
    mkdir -p "$OUT/linux-amd64"
    CGO_ENABLED=1 GOOS=linux GOARCH=amd64 \
        go build -buildmode=c-shared -mod=vendor \
        -o "$OUT/linux-amd64/libtailscale.so" .
    echo "    OK: $(du -sh "$OUT/linux-amd64/libtailscale.so" | cut -f1)"
}

# Создаёт враппер для zig cc (macOS-цели):
#  - Удаляет Linux/macOS-специфичные флаги
#  - Добавляет -Wl,-undefined,dynamic_lookup (символы pthread/CoreFoundation из JVM)
make_zig_cc() {
    local zig_target="$1"
    local wrapper
    wrapper="$(mktemp /tmp/zig-cc-XXXXXX.sh)"
    cat > "$wrapper" << 'WRAPPER_EOF'
#!/usr/bin/env bash
ZIG_TARGET="__TARGET__"
declare -a args=()
skip_next=false
for arg in "$@"; do
    if $skip_next; then
        skip_next=false
        continue
    fi
    case "$arg" in
        -Wl,--compress-debug-sections=*) ;;  # Linux ld only
        -lresolv|-lpthread)              ;;  # часть libSystem на macOS
        -framework) skip_next=true      ;;  # пропустить "-framework <Name>"
        *) args+=("$arg")               ;;
    esac
done
exec zig cc -target "$ZIG_TARGET" -Wl,-undefined,dynamic_lookup "${args[@]}"
WRAPPER_EOF
    sed -i "s/__TARGET__/$zig_target/" "$wrapper"
    chmod +x "$wrapper"
    echo "$wrapper"
}

# Создаёт враппер для zig cc (Windows-цель):
#  - Удаляет флаги, несовместимые с PE/COFF линкером
make_zig_cc_windows() {
    local wrapper
    wrapper="$(mktemp /tmp/zig-cc-win-XXXXXX.sh)"
    cat > "$wrapper" << 'WRAPPER_EOF'
#!/usr/bin/env bash
declare -a args=()
skip_next=false
for arg in "$@"; do
    if $skip_next; then
        skip_next=false
        continue
    fi
    case "$arg" in
        -Wl,--compress-debug-sections=*) ;;
        -Wl,-undefined,dynamic_lookup)   ;;  # ELF/Mach-O only
        -lresolv|-lpthread)              ;;
        -framework) skip_next=true      ;;
        *) args+=("$arg")               ;;
    esac
done
exec zig cc -target x86_64-windows-gnu "${args[@]}"
WRAPPER_EOF
    chmod +x "$wrapper"
    echo "$wrapper"
}

build_macos_amd64() {
    echo "==> macos-amd64 (via zig cc)"
    local cc; cc="$(make_zig_cc x86_64-macos)"
    CGO_ENABLED=1 GOOS=darwin GOARCH=amd64 \
        CC="$cc" \
        go build -buildmode=c-shared -mod=vendor \
        -o "$OUT/macos-amd64/libtailscale.dylib" .
    rm -f "$cc"
    echo "    OK: $(du -sh "$OUT/macos-amd64/libtailscale.dylib" | cut -f1)"
}

build_macos_arm64() {
    echo "==> macos-arm64 (via zig cc)"
    mkdir -p "$OUT/macos-arm64"
    local cc; cc="$(make_zig_cc aarch64-macos)"
    CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 \
        CC="$cc" \
        go build -buildmode=c-shared -mod=vendor \
        -o "$OUT/macos-arm64/libtailscale.dylib" .
    rm -f "$cc"
    echo "    OK: $(du -sh "$OUT/macos-arm64/libtailscale.dylib" | cut -f1)"
}

build_windows_amd64() {
    echo "==> windows-amd64 (via zig cc)"
    mkdir -p "$OUT/windows-amd64"
    local cc; cc="$(make_zig_cc_windows)"
    CGO_ENABLED=1 GOOS=windows GOARCH=amd64 \
        CC="$cc" \
        go build -buildmode=c-shared -mod=vendor \
        -o "$OUT/windows-amd64/tailscale.dll" .
    rm -f "$cc"
    echo "    OK: $(du -sh "$OUT/windows-amd64/tailscale.dll" | cut -f1)"
}

case "$TARGET" in
    linux-amd64)    build_linux_amd64   ;;
    macos-amd64)    build_macos_amd64   ;;
    macos-arm64)    build_macos_arm64   ;;
    windows-amd64)  build_windows_amd64 ;;
    all)
        build_linux_amd64
        build_macos_amd64
        build_macos_arm64
        build_windows_amd64
        ;;
    *)
        echo "Usage: $0 [linux-amd64|macos-amd64|macos-arm64|windows-amd64|all]"
        exit 1
        ;;
esac

echo "Done."
