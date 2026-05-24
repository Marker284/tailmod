package com.tailscale.tailmod.proxy;

import com.tailscale.tailmod.tailscale.LibTailscale;
import com.tailscale.tailmod.tailscale.TailscaleNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * UDP прокси для одного порта: 127.0.0.1:{port} → peer:{port} через tsnet.
 *
 * Для работы с войс/вэбкам модами сервер должен сообщать клиенту
 * адрес 127.0.0.1 (например, voice_host=127.0.0.1 в Simple Voice Chat).
 */
public class UdpPortProxy implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("tailmod");
    private static final int BUF = 65535;

    private final TailscaleNode node;
    private final int port;
    private final AtomicReference<String> targetIp = new AtomicReference<>();
    private final AtomicLong tsConn = new AtomicLong(0);

    private DatagramSocket sock;
    private volatile InetSocketAddress clientAddr;

    public UdpPortProxy(TailscaleNode node, int port) {
        this.node = node;
        this.port = port;
    }

    public int getPort() { return port; }

    public void setTarget(String ip) {
        targetIp.set(ip);
        long old = tsConn.getAndSet(0);
        if (old != 0) LibTailscale.INSTANCE.TailscaleConnClose(old);
        clientAddr = null;
    }

    public void start() throws SocketException {
        sock = new DatagramSocket(null);
        sock.setReuseAddress(true);
        sock.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        Thread.ofVirtual().name("UdpProxy-" + port + "-recv").start(this::recvLoop);
        LOGGER.info("[tailmod] UDP proxy on 127.0.0.1:{}", port);
    }

    @Override
    public void close() {
        if (sock != null) sock.close();
        long c = tsConn.getAndSet(0);
        if (c != 0) LibTailscale.INSTANCE.TailscaleConnClose(c);
    }

    private void recvLoop() {
        byte[] buf = new byte[BUF];
        while (!sock.isClosed()) {
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            try {
                sock.receive(pkt);
            } catch (IOException e) {
                if (!sock.isClosed()) LOGGER.warn("[tailmod] UDP:{} recv error", port, e);
                continue;
            }

            String target = targetIp.get();
            if (target == null) continue;

            InetSocketAddress src = new InetSocketAddress(pkt.getAddress(), pkt.getPort());

            if (tsConn.get() == 0 || !src.equals(clientAddr)) {
                long old = tsConn.getAndSet(0);
                if (old != 0) LibTailscale.INSTANCE.TailscaleConnClose(old);

                long conn = node.dialUdp(target, port);
                if (conn == 0) continue;

                if (!tsConn.compareAndSet(0, conn)) {
                    LibTailscale.INSTANCE.TailscaleConnClose(conn);
                    continue;
                }
                clientAddr = src;

                final long c = conn;
                final InetSocketAddress dst = src;
                Thread.ofVirtual().name("UdpProxy-" + port + "-send").start(() -> sendLoop(c, dst));
            }

            byte[] data = new byte[pkt.getLength()];
            System.arraycopy(pkt.getData(), pkt.getOffset(), data, 0, data.length);
            long c = tsConn.get();
            if (c != 0) LibTailscale.INSTANCE.TailscaleConnWrite(c, data, data.length);
        }
    }

    private void sendLoop(long conn, InetSocketAddress dst) {
        byte[] buf = new byte[BUF];
        while (!sock.isClosed() && tsConn.get() == conn) {
            int n = LibTailscale.INSTANCE.TailscaleConnRead(conn, buf, buf.length);
            if (n <= 0) break;
            try {
                sock.send(new DatagramPacket(buf, n, dst));
            } catch (IOException e) {
                if (!sock.isClosed()) LOGGER.warn("[tailmod] UDP:{} send error", port, e);
                break;
            }
        }
    }
}
