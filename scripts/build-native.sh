#!/usr/bin/env bash
# Сборка libtailscale для всех платформ.
#
# Использование:
#   nix-shell shell.nix --run "bash scripts/build-native.sh [linux-amd64|macos-amd64|macos-arm64|all]"

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/../native"

TARGET="${1:-all}"
OUT="$SCRIPT_DIR/../src/main/resources/natives"

build_linux_amd64() {
    echo "==> linux-amd64"
    CGO_ENABLED=1 GOOS=linux GOARCH=amd64 \
        go build -buildmode=c-shared \
        -o "$OUT/linux-amd64/libtailscale.so" .
    echo "    OK: $(du -sh "$OUT/linux-amd64/libtailscale.so" | cut -f1)"
}

# Создаёт враппер для zig cc который:
#  1. Удаляет Linux-специфичн��е флаги (-Wl,--compress-debug-sections, -lresolv, -lpthread)
#  2. Удаляет -framework <name> пары (Zig не имеет stubs для них)
#  3. Добавляет -Wl,-undefined,dynamic_lookup — символы CoreFoundation/Security/pthread
#     разрешаются из пространства символов JVM-процесса при загрузке dylib на Mac
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
    local cc; cc="$(make_zig_cc aarch64-macos)"
    CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 \
        CC="$cc" \
        go build -buildmode=c-shared -mod=vendor \
        -o "$OUT/macos-arm64/libtailscale.dylib" .
    rm -f "$cc"
    echo "    OK: $(du -sh "$OUT/macos-arm64/libtailscale.dylib" | cut -f1)"
}

case "$TARGET" in
    linux-amd64)  build_linux_amd64 ;;
    macos-amd64)  build_macos_amd64 ;;
    macos-arm64)  build_macos_arm64 ;;
    all)
        build_linux_amd64
        build_macos_amd64
        build_macos_arm64
        ;;
    *)
        echo "Usage: $0 [linux-amd64|macos-amd64|macos-arm64|all]"
        exit 1
        ;;
esac

echo "Done."
