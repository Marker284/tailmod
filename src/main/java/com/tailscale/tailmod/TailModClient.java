package com.tailscale.tailmod;

import com.tailscale.tailmod.proxy.TailProxy;
import com.tailscale.tailmod.proxy.UdpPortProxy;
import com.tailscale.tailmod.tailscale.TailscaleNode;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class TailModClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("tailmod");

    public enum State { IDLE, STARTING, READY, ERROR }

    private static volatile TailscaleNode node;
    private static volatile TailscaleNode pendingNode;  // in-progress during STARTING
    private static volatile TailProxy proxy;
    private static volatile List<UdpPortProxy> udpProxies = Collections.emptyList();
    private static volatile State state = State.IDLE;
    private static volatile String lastError = null;
    private static TailModConfig config;

    @Override
    public void onInitializeClient() {
        config = TailModConfig.load();
        LOGGER.info("[tailmod] Initialized");
        String key = config.getAuthKey();
        // Авто-коннект если есть ключ ИЛИ tsnet уже сохранил стейт (браузерная авторизация)
        if (!key.isBlank() || hasTsnetState()) {
            LOGGER.info("[tailmod] Auto-connecting (savedKey={}, savedState={})",
                    !key.isBlank(), hasTsnetState());
            startAsync(key.isBlank() ? null : key, success -> {
                if (success) LOGGER.info("[tailmod] Auto-connect successful");
                else         LOGGER.warn("[tailmod] Auto-connect failed: {}", lastError);
            });
        }
    }

    private static boolean hasTsnetState() {
        File dir = FabricLoader.getInstance().getConfigDir().resolve("tailmod").toFile();
        File[] files = dir.listFiles();
        return files != null && files.length > 0;
    }

    /** Возвращает true если хост входит в Tailscale IPv4-диапазон 100.64.0.0/10. */
    public static boolean isTailscaleAddress(String host) {
        try {
            String[] p = host.split("\\.");
            if (p.length != 4) return false;
            int a = Integer.parseInt(p[0]);
            int b = Integer.parseInt(p[1]);
            return a == 100 && b >= 64 && b <= 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static State getState()          { return state; }
    public static String getLastError()     { return lastError; }
    public static TailModConfig getConfig() { return config; }

    /**
     * Запускает подключение.
     * @param authKey ключ авторизации, или null/пустой для авторизации через браузер
     * @param onDone  вызывается с true при успехе или false при ошибке
     * @param onAuthUrl вызывается с URL когда требуется браузерная авторизация (может быть null)
     */
    public static void startAsync(String authKey, Consumer<Boolean> onDone, Consumer<String> onAuthUrl) {
        if (state == State.STARTING || state == State.READY) {
            onDone.accept(state == State.READY);
            return;
        }
        state = State.STARTING;

        CompletableFuture.runAsync(() -> {
            TailscaleNode n = null;
            try {
                boolean browserAuth = (authKey == null || authKey.isBlank());
                n = new TailscaleNode(browserAuth ? null : authKey);
                pendingNode = n;

                // Запускаем tsnet (не блокирует)
                n.startServer();

                // Если браузерная авторизация — опрашиваем URL параллельно
                if (browserAuth && onAuthUrl != null) {
                    final TailscaleNode fn = n;
                    Thread.ofVirtual().name("tailmod-authurl-poll").start(() -> pollAuthUrl(fn, onAuthUrl));
                }

                // Ждём подключения (до 5 мин согласно TailscaleUp timeout)
                n.waitConnected();

                if (state != State.STARTING) {
                    // Cancelled while waiting
                    n.close();
                    pendingNode = null;
                    return;
                }

                TailProxy p = new TailProxy(n);
                p.start();

                List<UdpPortProxy> proxies = new ArrayList<>();
                for (int port : config.getUdpPorts()) {
                    try {
                        UdpPortProxy pp = new UdpPortProxy(n, port);
                        pp.start();
                        proxies.add(pp);
                    } catch (SocketException e) {
                        LOGGER.warn("[tailmod] Cannot bind UDP:{} — {}", port, e.getMessage());
                    }
                }

                node        = n;
                pendingNode = null;
                proxy       = p;
                udpProxies  = Collections.unmodifiableList(proxies);
                state       = State.READY;
                LOGGER.info("[tailmod] Ready ({} UDP proxies)", proxies.size());
                LanDiscovery.startBeacon(n);
                onDone.accept(true);
            } catch (Throwable e) {
                if (state != State.STARTING) return; // cancelled
                LOGGER.error("[tailmod] Start failed", e);
                lastError = e.getMessage();
                pendingNode = null;
                state = State.ERROR;
                onDone.accept(false);
            }
        });
    }

    /** Удобный метод для запуска с ключом (без browser-auth callback). */
    public static void startAsync(String authKey, Consumer<Boolean> onDone) {
        startAsync(authKey, onDone, null);
    }

    private static void pollAuthUrl(TailscaleNode n, Consumer<String> callback) {
        while (state == State.STARTING) {
            try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            if (state != State.STARTING) return;
            String url = n.getAuthUrl();
            if (url != null) {
                LOGGER.info("[tailmod] Auth URL: {}", url);
                callback.accept(url);
                return;
            }
        }
    }

    public static void setTarget(String peerIp, int mcPort) {
        if (proxy != null) proxy.setTarget(peerIp, mcPort);
        for (UdpPortProxy pp : udpProxies) pp.setTarget(peerIp);
    }

    public static TailscaleNode getNode() {
        if (node == null) throw new IllegalStateException("Tailscale node not ready");
        return node;
    }

    public static TailProxy getProxy() {
        if (proxy == null) throw new IllegalStateException("Proxy not ready");
        return proxy;
    }

    public static void shutdown() {
        state = State.IDLE;  // signal to in-flight threads before closing
        LanDiscovery.stopBeacon();
        for (UdpPortProxy pp : udpProxies) pp.close();
        udpProxies = Collections.emptyList();
        if (proxy != null) { proxy.close(); proxy = null; }
        if (node  != null) { node.close();  node  = null; }
        TailscaleNode pn = pendingNode;
        if (pn != null) { pendingNode = null; pn.close(); }
        lastError = null;
    }
}
