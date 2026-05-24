package com.tailscale.tailmod.proxy;

import com.tailscale.tailmod.tailscale.LibTailscale;
import com.tailscale.tailmod.tailscale.TailscaleNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

public class TailProxy implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("tailmod");
    public static final int LOCAL_PORT = 25566;
    private static final int BUF_SIZE  = 65536;

    private record Target(String ip, int port) {}

    private final TailscaleNode node;
    private final AtomicReference<Target> pendingTarget = new AtomicReference<>();

    private ServerSocket serverSocket;

    public TailProxy(TailscaleNode node) { this.node = node; }

    public void start() throws IOException {
        serverSocket = new ServerSocket(LOCAL_PORT, 1, InetAddress.getLoopbackAddress());
        serverSocket.setReuseAddress(true);
        Thread.ofVirtual().name("TailProxy-accept").start(this::acceptLoop);
        LOGGER.info("[tailmod] Proxy listening on 127.0.0.1:{}", LOCAL_PORT);
    }

    public void setTarget(String peerIp, int port) {
        pendingTarget.set(new Target(peerIp, port));
    }

    @Override
    public void close() {
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                Target t = pendingTarget.getAndSet(null);
                if (t == null) { client.close(); continue; }
                Thread.ofVirtual().name("TailProxy-bridge").start(() -> bridge(client, t));
            } catch (IOException e) {
                if (!serverSocket.isClosed()) LOGGER.warn("[tailmod] accept error", e);
            }
        }
    }

    private void bridge(Socket client, Target t) {
        long conn = 0;
        try {
            conn = node.dial(t.ip(), t.port());
            final long tsConn = conn;

            Thread mcToTs = Thread.ofVirtual().name("TailProxy-mc2ts").start(() -> {
                byte[] buf = new byte[BUF_SIZE];
                try (var in = client.getInputStream()) {
                    int n;
                    while ((n = in.read(buf)) != -1)
                        LibTailscale.INSTANCE.TailscaleConnWrite(tsConn, buf, n);
                } catch (IOException ignored) {}
            });

            byte[] buf = new byte[BUF_SIZE];
            try (var out = client.getOutputStream()) {
                int n;
                while ((n = LibTailscale.INSTANCE.TailscaleConnRead(tsConn, buf, buf.length)) > 0)
                    out.write(buf, 0, n);
            }
            mcToTs.join(1000);
        } catch (Exception e) {
            LOGGER.warn("[tailmod] Bridge error for {}:{}", t.ip(), t.port(), e);
        } finally {
            if (conn != 0) LibTailscale.INSTANCE.TailscaleConnClose(conn);
            try { client.close(); } catch (IOException ignored) {}
        }
    }
}
