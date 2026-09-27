package pangch.pangchProfileToBlockMod.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import java.awt.GraphicsEnvironment;
import org.slf4j.LoggerFactory;

public class PangchProfileToBlockModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Minecraft's Main sets this to true, which prevents a separate Swing window.
        System.setProperty("java.awt.headless", "false");
        LoggerFactory.getLogger("Pangch Profiling").info("AWT headless: {}", GraphicsEnvironment.isHeadless());
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("pangch-profile-to-block-mod", "profiling"));
        KeyMapping open = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.pangch-profile-to-block-mod.open", InputConstants.Type.KEYBOARD, InputConstants.KEY_F12, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (open.consumeClick()) {
                ProfileWindow.toggle(client);
            }
        });
    }
}
