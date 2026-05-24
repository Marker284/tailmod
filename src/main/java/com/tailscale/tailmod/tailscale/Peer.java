package com.tailscale.tailmod.tailscale;

import java.util.List;

/** Данные о пире из TailscaleStatus JSON. */
public record Peer(
        String hostName,
        List<String> tailscaleIPs,
        boolean online,
        int pingMs         // -1 пока не измерен
) {
    /** IPv4-адрес пира, либо пустая строка. */
    public String ipv4() {
        return tailscaleIPs.stream()
                .filter(ip -> !ip.contains(":"))
                .findFirst()
                .orElse("");
    }

    public String displayName() {
        return hostName.isEmpty() ? ipv4() : hostName;
    }
}
