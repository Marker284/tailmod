package com.tailscale.tailmod;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.tailscale.tailmod.tailscale.LibTailscale;
import com.tailscale.tailmod.tailscale.TailscaleNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Modern SLP ping (1.7+) через tsnet-соединение.
 * Отправляет Handshake + Status Request, читает JSON-ответ.
 */
public class SlpPinger {

    private static final Logger LOGGER = LoggerFactory.getLogger("tailmod");
    private static final Gson GSON = new Gson();
    private static final int TIMEOUT_MS = 3000;

    public record Result(String version, int online, int max, long pingMs) {}

    public static Result ping(TailscaleNode node, String ip, int port) {
        AtomicLong connRef = new AtomicLong(0);
        Result[] out = {null};

        Thread t = Thread.ofVirtual().start(() -> {
            long conn = node.dialSilent(ip, port);
            if (conn == 0) return;
            connRef.set(conn);
            try {
                out[0] = doSlp(conn, ip, port);
            } finally {
                LibTailscale.INSTANCE.TailscaleConnClose(conn);
                connRef.set(0);
            }
        });

        try { t.join(TIMEOUT_MS); } catch (InterruptedException ignored) {}

        if (t.isAlive()) {
            long conn = connRef.getAndSet(0);
            if (conn != 0) LibTailscale.INSTANCE.TailscaleConnClose(conn);
        }

        return out[0];
    }

    private static Result doSlp(long conn, String host, int port) {
        try {
            long start = System.currentTimeMillis();

            byte[] packet = buildStatusPacket(host, port);
            if (LibTailscale.INSTANCE.TailscaleConnWrite(conn, packet, packet.length) <= 0)
                return null;

            // Читаем: [VarInt length] [VarInt packetId=0] [VarInt strLen] [JSON bytes]
            int totalLen = readVarInt(conn);
            if (totalLen <= 0) return null;
            int packetId = readVarInt(conn);
            if (packetId != 0) return null;
            int strLen = readVarInt(conn);
            if (strLen <= 0 || strLen > 65536) return null;

            byte[] jsonBytes = readExact(conn, strLen);
            if (jsonBytes == null) return null;

            return parseJson(new String(jsonBytes, StandardCharsets.UTF_8),
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            LOGGER.debug("[tailmod] SLP {}: {}", host, e.getMessage());
            return null;
        }
    }

    private static byte[] buildStatusPacket(String host, int port) throws Exception {
        // Handshake data
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        writeVarInt(data, 0x00);        // packet ID
        writeVarInt(data, 0);           // protocol version (0 = ping)
        writeString(data, host);        // server address
        data.write((port >> 8) & 0xFF); // port high byte
        data.write(port & 0xFF);        // port low byte
        writeVarInt(data, 1);           // next state: status

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, data.size());
        out.write(data.toByteArray());
        // Status request (length=1, id=0)
        out.write(0x01);
        out.write(0x00);
        return out.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) { out.write(value); return; }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private static void writeString(ByteArrayOutputStream out, String s) throws Exception {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static int readVarInt(long conn) throws Exception {
        int result = 0, shift = 0;
        for (int i = 0; i < 5; i++) {
            byte[] b = new byte[1];
            if (LibTailscale.INSTANCE.TailscaleConnRead(conn, b, 1) <= 0)
                throw new Exception("EOF");
            result |= (b[0] & 0x7F) << shift;
            if ((b[0] & 0x80) == 0) return result;
            shift += 7;
        }
        throw new Exception("VarInt too long");
    }

    private static byte[] readExact(long conn, int count) {
        byte[] result = new byte[count];
        int read = 0;
        while (read < count) {
            byte[] buf = new byte[Math.min(count - read, 4096)];
            int n = LibTailscale.INSTANCE.TailscaleConnRead(conn, buf, buf.length);
            if (n <= 0) return null;
            System.arraycopy(buf, 0, result, read, n);
            read += n;
        }
        return result;
    }

    private static Result parseJson(String json, long pingMs) {
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            String version = "?";
            int online = 0, max = 0;
            if (root.has("version"))
                version = root.getAsJsonObject("version").get("name").getAsString();
            if (root.has("players")) {
                JsonObject p = root.getAsJsonObject("players");
                online = p.has("online") ? p.get("online").getAsInt() : 0;
                max    = p.has("max")    ? p.get("max").getAsInt()    : 0;
            }
            return new Result(version, online, max, pingMs);
        } catch (Exception e) {
            return null;
        }
    }
}
