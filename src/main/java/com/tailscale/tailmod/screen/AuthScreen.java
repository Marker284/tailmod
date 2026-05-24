package com.tailscale.tailmod.screen;

import com.tailscale.tailmod.TailModClient;
import com.tailscale.tailmod.TailModConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class AuthScreen extends Screen {

    private final Screen parent;
    private EditBox keyField;
    private Button submitBtn;
    private Button browserBtn;
    private Button openBrowserBtn;  // виден только когда authUrl != null

    private volatile String statusMsg   = "";
    private volatile int    statusColor = 0xFFAAAAAA;
    private volatile String authUrl     = null;

    private int tickCount = 0;

    public AuthScreen(Screen parent) {
        super(Component.translatable("gui.tailmod.auth_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;

        keyField = addRenderableWidget(new EditBox(
                font, cx - 150, cy - 10, 300, 20,
                Component.translatable("gui.tailmod.auth_prompt")
        ));
        keyField.setMaxLength(256);
        keyField.setHint(Component.translatable("gui.tailmod.auth_prompt"));

        String envKey = System.getProperty("tailmod.authkey", "");
        if (!envKey.isBlank()) {
            keyField.setValue(envKey);
        } else {
            String saved = TailModClient.getConfig().getAuthKey();
            if (!saved.isBlank()) keyField.setValue(saved);
        }

        submitBtn = addRenderableWidget(Button.builder(
                Component.translatable("gui.tailmod.auth_submit"),
                btn -> submit()
        ).bounds(cx - 75, cy + 18, 148, 20).build());

        browserBtn = addRenderableWidget(Button.builder(
                Component.translatable("gui.tailmod.auth_browser"),
                btn -> startBrowserAuth()
        ).bounds(cx - 75, cy + 42, 148, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.cancel"),
                btn -> {
                    if (TailModClient.getState() == TailModClient.State.STARTING) {
                        TailModClient.shutdown();
                    }
                    minecraft.setScreen(parent);
                }
        ).bounds(cx - 75, cy + 66, 148, 20).build());

        // Кнопка "Открыть браузер" — скрыта до получения URL
        openBrowserBtn = addRenderableWidget(Button.builder(
                Component.translatable("gui.tailmod.auth_open_browser"),
                btn -> clickOpenBrowser()
        ).bounds(cx - 75, cy + 116, 148, 20).build());
        openBrowserBtn.visible = false;

        setInitialFocus(keyField);

        if (TailModClient.getState() == TailModClient.State.STARTING) {
            setConnecting();
            if (authUrl != null) openBrowserBtn.visible = true;
        }
    }

    @Override
    public void tick() {
        if (TailModClient.getState() == TailModClient.State.STARTING) {
            tickCount++;
            int dots = (tickCount / 8) % 4;
            statusMsg   = (authUrl != null ? "Waiting for browser login" : "Connecting") + ".".repeat(dots);
            statusColor = 0xFFFFFF55;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        int cx = width / 2;
        int cy = height / 2;

        g.centeredText(font, title, cx, cy - 40, 0xFFFFFFFF);

        if (!statusMsg.isEmpty()) {
            g.centeredText(font, statusMsg, cx, cy + 94, statusColor);
        }

        // URL под статусом (для копирования глазами)
        String url = authUrl;
        if (url != null) {
            String display = url.length() > 52 ? url.substring(0, 49) + "..." : url;
            g.centeredText(font, display, cx, cy + 106, 0xFF55AAFF);
        }
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (keyField.isFocused() && event.key() == 257) { // GLFW_KEY_ENTER
            submit();
            return true;
        }
        return super.keyPressed(event);
    }

    private void setConnecting() {
        submitBtn.active  = false;
        browserBtn.active = false;
        keyField.setEditable(false);
        statusMsg   = "Connecting...";
        statusColor = 0xFFFFFF55;
    }

    private void submit() {
        String key = keyField.getValue().trim();
        if (key.isEmpty()) {
            statusMsg   = "Enter an auth key first";
            statusColor = 0xFFFF5555;
            return;
        }
        setConnecting();

        TailModClient.startAsync(key, success -> minecraft.execute(() -> {
            if (success) {
                TailModConfig cfg = TailModClient.getConfig();
                cfg.setAuthKey(key);
                cfg.save();
                statusMsg   = "Connected!";
                statusColor = 0xFF55FF55;
                minecraft.setScreen(new TailnetScreen(parent));
            } else {
                String err = TailModClient.getLastError();
                statusMsg         = err != null ? err : "Unknown error";
                statusColor       = 0xFFFF5555;
                submitBtn.active  = true;
                browserBtn.active = true;
                keyField.setEditable(true);
            }
        }));
    }

    private void startBrowserAuth() {
        setConnecting();
        authUrl = null;
        if (openBrowserBtn != null) openBrowserBtn.visible = false;

        TailModClient.startAsync(null, success -> minecraft.execute(() -> {
            if (success) {
                statusMsg   = "Connected!";
                statusColor = 0xFF55FF55;
                minecraft.setScreen(new TailnetScreen(parent));
            } else {
                String err = TailModClient.getLastError();
                statusMsg              = err != null ? err : "Unknown error";
                statusColor            = 0xFFFF5555;
                authUrl                = null;
                openBrowserBtn.visible = false;
                submitBtn.active       = true;
                browserBtn.active      = true;
                keyField.setEditable(true);
            }
        }), url -> minecraft.execute(() -> {
            authUrl                = url;
            openBrowserBtn.visible = true;
            statusMsg   = "Click button or copy the link";
            statusColor = 0xFFFFFF55;
            // Авто-попытка открыть браузер
            tryOpenBrowser(url);
        }));
    }

    private void clickOpenBrowser() {
        String url = authUrl;
        if (url == null) return;
        // Всегда копируем в буфер обмена
        minecraft.keyboardHandler.setClipboard(url);
        statusMsg   = "Copied! Also opening browser...";
        statusColor = 0xFF55FF55;
        tryOpenBrowser(url);
    }

    /** Открывает б��аузер через систе��ную команду (xdg-open / open / start). */
    private void tryOpenBrowser(String url) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", "start", "", url);
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", url);
            } else {
                pb = new ProcessBuilder("xdg-open", url);
            }
            pb.start();
        } catch (Exception ignored) {
            // URL уже скопирован в буфер — пользователь может вставить вручную
        }
    }
}
