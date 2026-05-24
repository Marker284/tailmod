{ pkgs ? import <nixpkgs> {} }:
pkgs.mkShell {
  buildInputs = with pkgs; [
    jdk25
    gradle_9
    go
    gcc
    pkg-config
    zig   # кросс-компиляция CGo → macOS без macOS SDK
  ];
  shellHook = ''
    export JAVA_HOME="${pkgs.jdk25}"
    export GOPATH="$HOME/.cache/go"
    export CGO_ENABLED=1
  '';
}
