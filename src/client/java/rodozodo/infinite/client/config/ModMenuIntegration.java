package rodozodo.infinite.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;


// ModMenu entrypoint exposing Infinite's standalone settings screen

public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return InfiniteSettingsScreen::new;
    }
}
