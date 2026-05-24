package com.tailscale.tailmod.tailscale;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * JNA-биндинги к libtailscale (официальная C-обёртка над tsnet).
 * Сигнатуры соответствуют https://github.com/tailscale/libtailscale
 */
public interface LibTailscale extends Library {

    LibTailscale INSTANCE = Native.load("tailscale", LibTailscale.class);

    // --- Lifecycle ---

    /** Создаёт новый экземпляр ноды. Возвращает хэндл. */
    long TailscaleNew();

    /** Задаёт директорию хранения состояния. */
    int TailscaleSetDir(long ts, String dir);

    /** Задаёт hostname ноды (виден другим в сети). */
    int TailscaleSetHostname(long ts, String hostname);

    /** Задаёт auth key для авторизации без браузера. */
    int TailscaleSetAuthKey(long ts, String authKey);

    /** Делает ноду эфемерной (не остаётся в панели управления). */
    int TailscaleSetEphemeral(long ts, int ephemeral);

    /** Запускает ноду (не блокирует). */
    int TailscaleStart(long ts);

    /**
     * Ждёт установки соединения с tailnet.
     * ctx = 0 → без таймаута.
     */
    int TailscaleUp(long ts);

    /** Останавливает и освобождает ресурсы ноды. */
    int TailscaleClose(long ts);

    // --- Сетевые адреса ноды ---

    int TailscaleIPv4(long ts, byte[] buf, int bufLen);
    int TailscaleIPv6(long ts, byte[] buf, int bufLen);

    // --- Подключение к пирам ---

    /**
     * Открывает соединение к addr через tailnet.
     * network = "tcp", addr = "100.x.x.x:25565"
     * Возвращает хэндл соединения или 0 при ошибке.
     */
    long TailscaleDial(long ts, String network, String addr);

    int TailscaleConnRead(long conn, byte[] buf, int bufLen);
    int TailscaleConnWrite(long conn, byte[] buf, int bufLen);
    int TailscaleConnClose(long conn);

    // --- Статус (JSON) ---

    /**
     * Записывает JSON статуса в buf.
     * Формат: { "Self": {...}, "Peer": { "nodeKey": {...} } }
     */
    int TailscaleStatus(long ts, byte[] buf, int bufLen);

    // --- Авторизация через браузер ---

    /**
     * Возвращает URL для авторизации через браузер.
     * 0 = URL записан в buf, 1 = уже авторизован, -1 = ошибка.
     */
    int TailscaleGetAuthURL(long ts, byte[] buf, int bufLen);

    // --- Входящие соединения (tsnet listener) ---

    /** Слушает входящие tsnet-соединения. Возвращает хэндл listener или 0. */
    long TailscaleListen(long ts, String network, String addr);

    /** Блокирует до нового соединения. Возвращает TailscaleConn или 0. */
    long TailscaleAccept(long listener);

    /** Закрывает listener. */
    int TailscaleListenerClose(long listener);

    // --- Ошибки ---

    /** Возвращает строку последней ошибки для данного хэндла. */
    String TailscaleErrmsg(long ts);
}
