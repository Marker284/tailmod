package com.tailscale.tailmod.tailscale;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Высокоуровневая обёртка над LibTailscale.
 * Управляет lifecycle ноды и предоставляет список пиров.
 */
public class TailscaleNode implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("tailmod");
    private static final int STATUS_BUF = 65536;
    private static final Gson GSON = new Gson();

    private final long handle;
    private volatile boolean started = false;

    public TailscaleNode(String authKey) {
        NativeLoader.load();

        LOGGER.info("[tailmod] TailscaleNew...");
        handle = LibTailscale.INSTANCE.TailscaleNew();
        LOGGER.info("[tailmod] handle={}", handle);
        if (handle == 0) throw new RuntimeException("TailscaleNew() returned 0");

        Path stateDir = FabricLoader.getInstance().getConfigDir().resolve("tailmod");
        stateDir.toFile().mkdirs();
        LOGGER.info("[tailmod] stateDir={}", stateDir);

        check(LibTailscale.INSTANCE.TailscaleSetDir(handle, stateDir.toString()));
        check(LibTailscale.INSTANCE.TailscaleSetHostname(handle, "minecraft-client"));
        check(LibTailscale.INSTANCE.TailscaleSetEphemeral(handle, 1));
        if (authKey != null && !authKey.isBlank()) {
            LOGGER.info("[tailmod] Setting auth key (len={})", authKey.length());
            check(LibTailscale.INSTANCE.TailscaleSetAuthKey(handle, authKey));
        } else {
            LOGGER.warn("[tailmod] No auth key provided — tsnet will wait for browser auth");
        }
    }

    public void start() throws IOException {
        startServer();
        waitConnected();
    }

    /** Запускает фоновые процессы tsnet (не блокирует). */
    public void startServer() throws IOException {
        if (started) return;
        LOGGER.info("[tailmod] TailscaleStart...");
        int rc = LibTailscale.INSTANCE.TailscaleStart(handle);
        if (rc != 0) throw new IOException("TailscaleStart: " + errmsg());
        LOGGER.info("[tailmod] TailscaleStart OK");
    }

    /** Блокирует до установки соединения с tailnet (до 10 мин). */
    public void waitConnected() throws IOException {
        LOGGER.info("[tailmod] TailscaleUp (waiting for tailnet)...");
        int rc = LibTailscale.INSTANCE.TailscaleUp(handle);
        if (rc != 0) throw new IOException("TailscaleUp: " + errmsg());
        started = true;
        LOGGER.info("[tailmod] Connected to tailnet!");
    }

    /**
     * Возвращает URL для авторизации через браузер, или null если авторизация не нужна.
     * Работает после {@link #startServer()}.
     */
    public String getAuthUrl() {
        byte[] buf = new byte[512];
        int rc = LibTailscale.INSTANCE.TailscaleGetAuthURL(handle, buf, buf.length);
        if (rc != 0) return null; // rc=1: уже авторизован, rc=-1: ошибка
        String url = new String(buf, java.nio.charset.StandardCharsets.UTF_8).trim().replaceAll("\0.*", "");
        return url.isEmpty() ? null : url;
    }

    public List<Peer> peers() {
        byte[] buf = new byte[STATUS_BUF];
        int rc = LibTailscale.INSTANCE.TailscaleStatus(handle, buf, buf.length);
        if (rc != 0) {
            LOGGER.warn("[tailmod] TailscaleStatus error: {}", errmsg());
            return List.of();
        }
        String json = new String(buf, StandardCharsets.UTF_8).trim().replaceAll("\0.*", "");
        return parsePeers(json);
    }

    /**
     * Открывает raw TCP-соединение к пиру через tailnet.
     * Возвращает хэндл TailscaleConn (передаётся в TailProxy).
     */
    public long dial(String ip, int port) throws IOException {
        String addr = ip + ":" + port;
        long conn = LibTailscale.INSTANCE.TailscaleDial(handle, "tcp", addr);
        if (conn == 0) throw new IOException("TailscaleDial(" + addr + "): " + errmsg());
        return conn;
    }

    /** Слушает входящие tsnet-соединения. Возвращает хэндл listener. */
    public long listen(String network, String addr) throws IOException {
        long ln = LibTailscale.INSTANCE.TailscaleListen(handle, network, addr);
        if (ln == 0) throw new IOException("TailscaleListen(" + addr + "): " + errmsg());
        return ln;
    }

    /** Как {@link #dial}, но возвращает 0 вместо исключения — для SLP-пинга. */
    public long dialSilent(String ip, int port) {
        try { return dial(ip, port); } catch (IOException e) { return 0; }
    }

    public long dialUdp(String ip, int port) {
        String addr = ip + ":" + port;
        long conn = LibTailscale.INSTANCE.TailscaleDial(handle, "udp", addr);
        if (conn == 0) LOGGER.warn("[tailmod] UDP dial({}) failed: {}", addr, errmsg());
        return conn;
    }

    @Override
    public void close() {
        LibTailscale.INSTANCE.TailscaleClose(handle);
        started = false;
    }

    // --- helpers ---

    private String errmsg() {
        return LibTailscale.INSTANCE.TailscaleErrmsg(handle);
    }

    private void check(int rc) {
        if (rc != 0) throw new RuntimeException("libtailscale error: " + errmsg());
    }

    private static List<Peer> parsePeers(String json) {
        List<Peer> result = new ArrayList<>();
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            JsonObject peerMap = root.getAsJsonObject("Peer");
            if (peerMap == null) return result;

            for (Map.Entry<String, JsonElement> entry : peerMap.entrySet()) {
                JsonObject p = entry.getValue().getAsJsonObject();
                String hostName = p.has("HostName") ? p.get("HostName").getAsString() : "";
                boolean online  = p.has("Online") && p.get("Online").getAsBoolean();

                List<String> ips = new ArrayList<>();
                if (p.has("TailscaleIPs")) {
                    for (JsonElement ip : p.getAsJsonArray("TailscaleIPs")) {
                        ips.add(ip.getAsString());
                    }
                }
                result.add(new Peer(hostName, ips, online, -1));
            }
        } catch (Exception e) {
            LOGGER.warn("[tailmod] Failed to parse status JSON", e);
        }
        return result;
    }
}
