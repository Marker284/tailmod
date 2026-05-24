# Tailnet Minecraft Mod

## Цель
Мод, который добавляет кнопку "Tailnet" в MultiplayerScreen.
Юзер видит список своих устройств из тайлнета и коннектится к
Minecraft-серверам на них одним кликом.

## Архитектура
- libtailscale (C-обёртка вокруг Go tsnet) — userspace tailnet нода
  внутри JVM-процесса MC
- JNA для биндингов Java ↔ libtailscale
- Локальный TCP-прокси 127.0.0.1:25566 — мост между MC-сокетами
  и tsnet (т.к. tsnet даёт сокеты только своему процессу)
- Mixin в MultiplayerScreen для кнопки
- TailnetScreen — список пиров с SLP-пингом порта 25565

## Платформа
- Minecraft 26.1.2
- Fabric Loader 0.19.1 / Fabric API 0.149.1+26.1.2
- Fabric Loom 1.15, Mojmap (Yarn упразднён в 26.1+), Java 25
- Нативки под Windows/Linux/macOS бандлятся в jar (~60-80 МБ)
  - linux-amd64, linux-arm64, windows-amd64, macos-amd64, macos-arm64
  - Путь в jar: /natives/<os>-<arch>/libtailscale.{so,dll,dylib}

## Что НЕ делаем
- Не бандлим tailscaled (не нужен — libtailscale достаточно)
- Exit nodes / subnet routing не поддерживаем (требует TUN + root)
- MagicDNS резолвим через tsnet API, не через системный
