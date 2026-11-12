package pl.holovis;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.ActionResult;
import org.lwjgl.glfw.GLFW;
import pl.holovis.gui.HoloScreen;

public class HoloVisClient implements ClientModInitializer {
    public static KeyBinding OPEN_MENU, SP_NEXT, SP_PREV, SP_TOGGLE;
    private static LivingEntity lastTarget;
    private static int lastTargetTicks;

    private static KeyBinding key(String name, int code) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(name, InputUtil.Type.KEYSYM, code, "key.categories.holovis"));
    }

    @Override
    public void onInitializeClient() {
        HoloConfig.load();
        HoloSounds.refresh();
        Spotify.sync();

        OPEN_MENU = key("key.holovis.open", GLFW.GLFW_KEY_RIGHT_SHIFT);
        SP_NEXT = key("key.holovis.spotify_next", GLFW.GLFW_KEY_RIGHT_BRACKET);
        SP_PREV = key("key.holovis.spotify_prev", GLFW.GLFW_KEY_LEFT_BRACKET);
        SP_TOGGLE = key("key.holovis.spotify_toggle", GLFW.GLFW_KEY_BACKSLASH);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (OPEN_MENU.wasPressed()) {
                if (mc.currentScreen == null && mc.player != null) mc.setScreen(new HoloScreen());
            }
            while (SP_NEXT.wasPressed()) Spotify.next();
            while (SP_PREV.wasPressed()) Spotify.previous();
            while (SP_TOGGLE.wasPressed()) Spotify.toggle();

            if (lastTarget != null) {
                lastTargetTicks++;
                if (!lastTarget.isAlive()) {
                    HoloConfig cfg = HoloConfig.get();
                    if (cfg.killSound.enabled) HoloSounds.play(cfg.killSound);
                    lastTarget = null;
                } else if (lastTargetTicks > 100) {
                    lastTarget = null;
                }
            }
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient && entity instanceof LivingEntity living) {
                HoloConfig cfg = HoloConfig.get();
                if (cfg.hitSound.enabled) HoloSounds.play(cfg.hitSound);
                lastTarget = living;
                lastTargetTicks = 0;
            }
            return ActionResult.PASS;
        });

        WorldRenderEvents.AFTER_ENTITIES.register(HitboxRenderer::render);
        HudRenderCallback.EVENT.register(HudRenderer::render);
    }
}
