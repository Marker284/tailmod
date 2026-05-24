package com.tailscale.tailmod.screen;

import com.tailscale.tailmod.TailModClient;
import com.tailscale.tailmod.TailModConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class TailModSettingsScreen extends Screen {

    private final Screen parent;
    private EditBox keyField;
    private EditBox portsField;

    public TailModSettingsScreen(Screen parent) {
        super(Component.translatable("gui.tailmod.settings_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;

        // Auth key
        keyField = addRenderableWidget(new EditBox(
                font, cx - 150, cy - 40, 300, 20,
                Component.translatable("gui.tailmod.auth_prompt")
        ));
        keyField.setMaxLength(256);
        keyField.setHint(Component.translatable("gui.tailmod.auth_prompt"));
        keyField.setValue(TailModClient.getConfig().getAuthKey());

        // UDP ports (comma-separated)
        portsField = addRenderableWidget(new EditBox(
                font, cx - 150, cy - 10, 300, 20,
                Component.translatable("gui.tailmod.settings_ports_hint")
        ));
        portsField.setMaxLength(256);
        portsField.setHint(Component.translatable("gui.tailmod.settings_ports_hint"));
        portsField.setValue(portsToString(TailModClient.getConfig().getUdpPorts()));

        addRenderableWidget(Button.builder(
                Component.translatable("gui.tailmod.settings_save"),
                btn -> save()
        ).bounds(cx - 100, cy + 18, 98, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.cancel"),
                btn -> minecraft.setScreen(parent)
        ).bounds(cx + 4, cy + 18, 98, 20).build());

        if (TailModClient.getState() == TailModClient.State.READY) {
            addRenderableWidget(Button.builder(
                    Component.translatable("gui.tailmod.settings_disconnect"),
                    btn -> { TailModClient.shutdown(); minecraft.setScreen(parent); }
            ).bounds(cx - 100, cy + 42, 202, 20).build());
        }

        setInitialFocus(keyField);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        int cx = width / 2;
        int cy = height / 2;

        g.centeredText(font, title, cx, cy - 70, 0xFFFFFFFF);

        TailModClient.State st = TailModClient.getState();
        String stateStr = switch (st) {
            case READY    -> "Connected";
            case STARTING -> "Connecting...";
            case ERROR    -> { String e = TailModClient.getLastError(); yield e != null ? e : "Error"; }
            default       -> "Disconnected";
        };
        int stateColor = switch (st) {
            case READY    -> 0xFF55FF55;
            case STARTING -> 0xFFFFFF55;
            case ERROR    -> 0xFFFF5555;
            default       -> 0xFFAAAAAA;
        };
        g.centeredText(font, stateStr, cx, cy - 56, stateColor);

        // Labels
        g.text(font, "Auth Key:", cx - 150, cy - 52, 0xFFAAAAAA, false);
        g.text(font, "UDP Ports:", cx - 150, cy - 22, 0xFFAAAAAA, false);
    }

    private void save() {
        TailModConfig cfg = TailModClient.getConfig();
        cfg.setAuthKey(keyField.getValue().trim());
        cfg.setUdpPorts(parsePorts(portsField.getValue()));
        cfg.save();
        minecraft.setScreen(parent);
    }

    private static String portsToString(List<Integer> ports) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ports.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(ports.get(i));
        }
        return sb.toString();
    }

    private static List<Integer> parsePorts(String text) {
        List<Integer> result = new ArrayList<>();
        for (String part : text.split("[,\\s]+")) {
            try {
                int port = Integer.parseInt(part.trim());
                if (port > 0 && port < 65536) result.add(port);
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }
}
