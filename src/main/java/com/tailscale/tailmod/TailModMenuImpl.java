package com.tailscale.tailmod;

import com.tailscale.tailmod.screen.TailModSettingsScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class TailModMenuImpl implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return TailModSettingsScreen::new;
    }
}
