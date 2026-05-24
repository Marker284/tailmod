package com.tailscale.tailmod.screen;

import com.tailscale.tailmod.LanDiscovery;
import com.tailscale.tailmod.SlpPinger;
import com.tailscale.tailmod.TailModClient;
import com.tailscale.tailmod.proxy.TailProxy;
import com.tailscale.tailmod.tailscale.Peer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.List;

public class TailnetScreen extends Screen {

    private static final int DEFAULT_MC_PORT = 25565;
    private static final long DOUBLE_CLICK_MS = 400;

    private final Screen parent;
    private PeerList peerList;
    private Button connectBtn;
    private EditBox portField;
    private int tickCount = 0;
    private boolean wasStarting = false;

    public TailnetScreen(Screen parent) {
        super(Component.translatable("gui.tailmod.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TailModClient.State st = TailModClient.getState();
        if (st == TailModClient.State.IDLE || st == TailModClient.State.ERROR) {
            minecraft.execute(() -> minecraft.setScreen(new AuthScreen(parent)));
            return;
        }

        peerList = new PeerList(minecraft, width, height - 96, 32, 42);
        addWidget(peerList);

        portField = addRenderableWidget(new EditBox(
                font, width / 2 + 6, height - 28, 48, 20,
                Component.literal("Port")
        ));
        portField.setMaxLength(5);
        portField.setValue(String.valueOf(DEFAULT_MC_PORT));

        connectBtn = addRenderableWidget(Button.builder(
                Component.translatable("gui.tailmod.connect"),
                btn -> connectToSelected()
        ).bounds(width / 2 - 100, height - 28, 104, 20).build());
        connectBtn.active = false;

        addRenderableWidget(Button.builder(
                Component.translatable("gui.tailmod.refresh"),
                btn -> refreshPeers()
        ).bounds(width / 2 - 100, height - 52, 202, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.cancel"),
                btn -> minecraft.setScreen(parent)
        ).bounds(width / 2 + 58, height - 28, 44, 20).build());

        refreshPeers();
    }

    @Override
    public void tick() {
        tickCount++;
        TailModClient.State st = TailModClient.getState();

        if (wasStarting && st == TailModClient.State.READY) {
            wasStarting = false;
            refreshPeers();
        }
        if (st == TailModClient.State.STARTING) wasStarting = true;

        if (st == TailModClient.State.ERROR || st == TailModClient.State.IDLE) {
            minecraft.setScreen(new AuthScreen(parent));
            return;
        }

        if (tickCount == 60 || tickCount % 200 == 0) refreshPeers();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        peerList.extractWidgetRenderState(g, mouseX, mouseY, delta);
        var seq = title.getVisualOrderText();
        g.text(font, seq, (width - font.width(seq)) / 2, 9, 0xFFFFFFFF, true);
        if (portField != null)
            g.text(font, ":", width / 2 + 2, height - 24, 0xFFAAAAAA, false);
        if (TailModClient.getState() == TailModClient.State.STARTING) {
            int dots = (tickCount / 8) % 4;
            g.centeredText(font, "Подключение к Tailnet" + ".".repeat(dots),
                    width / 2, height - 72, 0xFFFFFF55);
        }
    }

    private void refreshPeers() {
        if (peerList == null || connectBtn == null) return;
        peerList.clearAll();
        connectBtn.active = false;

        Thread.ofVirtual().name("tailmod-peer-refresh").start(() -> {
            var cfg = TailModClient.getConfig();
            List<PeerEntry> entries = TailModClient.getNode().peers().stream()
                    .sorted(Comparator
                            .<Peer, Boolean>comparing(p -> cfg.isFavorite(p.hostName()),
                                    Comparator.reverseOrder())
                            .thenComparing(Peer::online, Comparator.reverseOrder())
                            .thenComparing(Peer::displayName))
                    .map(PeerEntry::new)
                    .toList();

            minecraft.execute(() -> entries.forEach(peerList::addPeer));

            // SLP-пинг для онлайн пиров
            for (PeerEntry e : entries) {
                if (!e.peer.online() || e.peer.ipv4().isEmpty()) continue;
                final PeerEntry entry = e;
                Thread.ofVirtual().name("tailmod-slp-" + e.peer.hostName()).start(() -> {
                    SlpPinger.Result r = SlpPinger.ping(
                            TailModClient.getNode(), entry.peer.ipv4(), DEFAULT_MC_PORT);
                    if (r != null) entry.slpResult = r;
                });
                Thread.ofVirtual().name("tailmod-lan-" + e.peer.hostName()).start(() -> {
                    LanDiscovery.LanInfo lan = LanDiscovery.queryPeer(
                            TailModClient.getNode(), entry.peer.ipv4());
                    if (lan != null) entry.lanInfo = lan;
                });
            }
        });
    }

    private int getPort() {
        try {
            int p = Integer.parseInt(portField.getValue().trim());
            return (p > 0 && p < 65536) ? p : DEFAULT_MC_PORT;
        } catch (NumberFormatException e) {
            return DEFAULT_MC_PORT;
        }
    }

    void connectToSelected() {
        PeerEntry selected = peerList.getSelected();
        if (selected == null) return;
        String ip = selected.peer.ipv4();
        if (ip.isEmpty()) return;

        LanDiscovery.LanInfo lan = selected.lanInfo;
        int mcPort = (lan != null) ? lan.port() : getPort();
        TailModClient.setTarget(ip, mcPort);
        ConnectScreen.startConnecting(parent, minecraft,
                new ServerAddress("127.0.0.1", TailProxy.LOCAL_PORT),
                new ServerData(selected.peer.displayName() + ":" + mcPort,
                        "127.0.0.1:" + TailProxy.LOCAL_PORT, ServerData.Type.OTHER),
                false, null);
    }

    // --- inner classes ---

    private class PeerList extends ObjectSelectionList<PeerEntry> {
        PeerList(net.minecraft.client.Minecraft mc, int width, int height, int top, int itemHeight) {
            super(mc, width, height, top, itemHeight);
        }

        @Override public int getRowWidth() { return 310; }
        void clearAll()       { clearEntries(); }
        void addPeer(PeerEntry e) { addEntry(e); }
    }

    private class PeerEntry extends ObjectSelectionList.Entry<PeerEntry> {
        final Peer peer;
        volatile SlpPinger.Result slpResult = null;
        volatile LanDiscovery.LanInfo lanInfo = null;
        private long lastClickTime = 0;

        PeerEntry(Peer p) { this.peer = p; }

        @Override
        public Component getNarration() { return Component.literal(peer.displayName()); }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                   boolean isSelected, float delta) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            var cfg = TailModClient.getConfig();
            boolean fav = cfg.isFavorite(peer.hostName());

            // Звёздочка справа
            String star = fav ? "§e★" : "§7☆";
            g.text(font, star, x + w - 14, y + 4, 0xFFFFFFFF, false);

            // Имя
            g.text(font, peer.displayName(), x + 2, y + 2, 0xFFFFFFFF, true);

            // Статус строка — IP + online/offline + SLP
            String online = peer.online() ? "§aonline" : "§coffline";
            SlpPinger.Result slp = slpResult;
            if (slp != null) {
                String info = "§7" + slp.online() + "§8/§7" + slp.max()
                        + " §8| §7" + slp.version()
                        + " §8| §a" + slp.pingMs() + "ms";
                g.text(font, peer.ipv4() + "  " + online + "  " + info, x + 2, y + 14, 0xFFAAAAAA, false);
            } else {
                g.text(font, peer.ipv4() + "  " + online, x + 2, y + 14, 0xFFAAAAAA, false);
            }

            // LAN-мир
            LanDiscovery.LanInfo lan = lanInfo;
            if (lan != null) {
                g.text(font, "§b⬡ LAN: §f" + lan.motd(), x + 2, y + 24, 0xFFAAAAAA, false);
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
            if (event.button() != 0) return false;

            double ex = event.x();
            int starLeft = getX() + getWidth() - 20;

            // Клик по звёздочке — переключить избранное
            if (ex >= starLeft) {
                TailModClient.getConfig().toggleFavorite(peer.hostName());
                refreshPeers(); // пересортировать
                return true;
            }

            peerList.setSelected(this);
            connectBtn.active = !peer.ipv4().isEmpty();

            // Двойной клик — коннект
            long now = System.currentTimeMillis();
            if (now - lastClickTime < DOUBLE_CLICK_MS && !peer.ipv4().isEmpty())
                connectToSelected();
            lastClickTime = now;
            return true;
        }
    }
}
