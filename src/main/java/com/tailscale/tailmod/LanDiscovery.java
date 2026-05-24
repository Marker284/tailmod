package com.tailscale.tailmod;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.tailscale.tailmod.tailscale.LibTailscale;
import com.tailscale.tailmod.tailscale.TailscaleNode;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LAN-мир через Tailnet.
 * - Mixin на LanServerPinger сообщает нам когда открыт/закрыт LAN-мир.
 * - Beacon-сервер отвечает пирам на запросы "есть ли LAN-мир?".
 * - queryPeer() дозванивается до пира и возвращает его LAN-данные.
 */
public class LanDiscovery {

    private static final Logger LOGGER = LoggerFactory.getLogger("tailmod");
    private static final Gson GSON = new Gson();
    static final int BEACON_PORT = 25561;
    private static final int QUERY_TIMEOUT_MS = 2000;

    public record LanInfo(String motd, int port) {}

    private static volatile LanInfo localLan = null;
    private static volatile long listenerHandle = 0;

    // --- Вызывается из LanServerPingerMixin ---

    public static void onLanOpen(String motd, String serverAddress) {
        int port = parsePort(serverAddress);
        if (port > 0) {
            localLan = new LanInfo(motd, port);
            LOGGER.info("[tailmod] LAN opened: {} port {}", motd, port);
        }
    }

    public static void onLanClose() {
        localLan = null;
        LOGGER.info("[tailmod] LAN closed");
    }

    // --- Beacon server (запускается после подключения к tailnet) ---

    public static void startBeacon(TailscaleNode node) {
        Thread.ofVirtual().name("tailmod-lan-beacon").start(() -> runBeacon(node));
    }

    public static void stopBeacon() {
        long ln = listenerHandle;
        if (ln != 0) {
            listenerHandle = 0;
            LibTailscale.INSTANCE.TailscaleListenerClose(ln);
        }
        localLan = null;
    }

    private static void runBeacon(TailscaleNode node) {
        try {
            long ln = node.listen("tcp", ":" + BEACON_PORT);
            listenerHandle = ln;
            LOGGER.info("[tailmod] LAN beacon listening on :{}", BEACON_PORT);
            while (listenerHandle != 0) {
                long conn = LibTailscale.INSTANCE.TailscaleAccept(ln);
                if (conn == 0) break;
                final long c = conn;
                Thread.ofVirtual().start(() -> handleBeaconConn(c));
            }
        } catch (Exception e) {
            LOGGER.warn("[tailmod] LAN beacon error: {}", e.getMessage());
        }
    }

    private static void handleBeaconConn(long conn) {
        try {
            Minecraft mc = Minecraft.getInstance();
            var server = mc != null ? mc.getSingleplayerServer() : null;
            boolean published = server != null && server.isPublished();
            LanInfo info = localLan;

            JsonObject obj = new JsonObject();
            if (published && info != null) {
                obj.addProperty("motd", info.motd());
                obj.addProperty("port", info.port());
            }
            byte[] data = GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
            LibTailscale.INSTANCE.TailscaleConnWrite(conn, data, data.length);
        } finally {
            LibTailscale.INSTANCE.TailscaleConnClose(conn);
        }
    }

    // --- Опрос пира ---

    public static LanInfo queryPeer(TailscaleNode node, String peerIp) {
        AtomicLong connRef = new AtomicLong(0);
        LanInfo[] out = {null};

        Thread t = Thread.ofVirtual().start(() -> {
            long conn = node.dialSilent(peerIp, BEACON_PORT);
            if (conn == 0) return;
            connRef.set(conn);
            try {
                byte[] buf = new byte[2048];
                int n = LibTailscale.INSTANCE.TailscaleConnRead(conn, buf, buf.length);
                if (n <= 0) return;
                String json = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
                out[0] = parseResponse(json);
            } finally {
                LibTailscale.INSTANCE.TailscaleConnClose(conn);
                connRef.set(0);
            }
        });

        try { t.join(QUERY_TIMEOUT_MS); } catch (InterruptedException ignored) {}

        if (t.isAlive()) {
            long conn = connRef.getAndSet(0);
            if (conn != 0) LibTailscale.INSTANCE.TailscaleConnClose(conn);
        }

        return out[0];
    }

    // --- helpers ---

    private static LanInfo parseResponse(String json) {
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (!obj.has("port")) return null;
            String motd = obj.has("motd") ? obj.get("motd").getAsString() : "LAN World";
            int port = obj.get("port").getAsInt();
            return port > 0 ? new LanInfo(motd, port) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int parsePort(String addr) {
        try {
            int colon = addr.lastIndexOf(':');
            String portStr = colon >= 0 ? addr.substring(colon + 1) : addr;
            return Integer.parseInt(portStr.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
