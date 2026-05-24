package com.tailscale.tailmod.mixin;

import com.tailscale.tailmod.LanDiscovery;
import net.minecraft.client.server.LanServerPinger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LanServerPinger.class)
public class LanServerPingerMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void tailmod$onLanOpen(String motd, String serverAddress, CallbackInfo ci) {
        LanDiscovery.onLanOpen(motd, serverAddress);
    }

    @Inject(method = "interrupt", at = @At("HEAD"))
    private void tailmod$onLanClose(CallbackInfo ci) {
        LanDiscovery.onLanClose();
    }
}
