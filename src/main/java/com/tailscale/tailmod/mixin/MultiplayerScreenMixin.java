package com.tailscale.tailmod.mixin;

import com.tailscale.tailmod.screen.TailnetScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Добавляет кнопку "Tailnet" в экран мультиплеера.
 *
 * Кнопка располагается над стандартной строкой кнопок (y = height-52)
 * по центру, чтобы не перекрывать существующий UI.
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {

    protected MultiplayerScreenMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void tailmod$addTailnetButton(CallbackInfo ci) {
        addRenderableWidget(Button.builder(
                Component.translatable("gui.tailmod.open"),
                btn -> minecraft.setScreen(new TailnetScreen(this))
        ).bounds(width / 2 + 158, height - 52, 74, 20).build());
    }
}
