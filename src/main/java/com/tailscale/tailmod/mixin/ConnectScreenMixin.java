package com.tailscale.tailmod.mixin;

import com.tailscale.tailmod.TailModClient;
import com.tailscale.tailmod.proxy.TailProxy;
import com.tailscale.tailmod.screen.TailnetScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin extends Screen {

    @Shadow private Screen parent;

    protected ConnectScreenMixin(Component title) { super(title); }

    private static final ThreadLocal<Boolean> SKIP = ThreadLocal.withInitial(() -> false);

    @Inject(method = "connect", at = @At("HEAD"), cancellable = true)
    private void tailmod$interceptTailscale(Minecraft mc, ServerAddress address,
            ServerData serverData, TransferState transferState, CallbackInfo ci) {
        if (SKIP.get()) return;
        if (!TailModClient.isTailscaleAddress(address.getHost())) return;

        ci.cancel();
        String host = address.getHost();
        int port = address.getPort();
        Screen savedParent = parent;
        TailModClient.State st = TailModClient.getState();

        if (st == TailModClient.State.READY) {
            doConnect(mc, host, port, serverData, transferState, savedParent);
        } else if (st == TailModClient.State.STARTING) {
            // Авто-коннект ещё идёт — ждём в фоне, потом коннектимся
            Thread.ofVirtual().name("tailmod-await-connect").start(() -> {
                while (true) {
                    try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                    TailModClient.State cur = TailModClient.getState();
                    if (cur == TailModClient.State.READY) {
                        mc.execute(() -> doConnect(mc, host, port, serverData, transferState, savedParent));
                        return;
                    }
                    if (cur != TailModClient.State.STARTING) {
                        mc.execute(() -> mc.setScreen(new TailnetScreen(savedParent)));
                        return;
                    }
                }
            });
        } else {
            mc.execute(() -> mc.setScreen(new TailnetScreen(savedParent)));
        }
    }

    private static void doConnect(Minecraft mc, String host, int port,
            ServerData serverData, TransferState transferState, Screen savedParent) {
        TailModClient.setTarget(host, port);
        SKIP.set(true);
        try {
            ConnectScreen.startConnecting(
                    savedParent, mc,
                    new ServerAddress("127.0.0.1", TailProxy.LOCAL_PORT),
                    serverData, false, transferState
            );
        } finally {
            SKIP.set(false);
        }
    }
}
