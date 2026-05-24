package com.tailscale.tailmod.mixin;

import com.tailscale.tailmod.screen.TailnetScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void tailmod$splitMultiplayerButton(CallbackInfo ci) {
        Button mpBtn = null;
        for (var widget : children()) {
            if (widget instanceof Button btn &&
                    btn.getMessage().getContents() instanceof TranslatableContents tc &&
                    "menu.multiplayer".equals(tc.getKey())) {
                mpBtn = btn;
                break;
            }
        }
        if (mpBtn == null) return;

        int x = mpBtn.getX();
        int y = mpBtn.getY();
        int h = mpBtn.getHeight();
        int w = (mpBtn.getWidth() - 4) / 2; // обычно (200-4)/2 = 98

        mpBtn.setWidth(w);

        addRenderableWidget(Button.builder(
                Component.translatable("gui.tailmod.open"),
                btn -> minecraft.setScreen(new TailnetScreen(this))
        ).bounds(x + w + 4, y, w, h).build());
    }
}
