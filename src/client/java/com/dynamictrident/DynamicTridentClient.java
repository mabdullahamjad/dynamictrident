package com.dynamictrident;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

public class DynamicTridentClient implements ClientModInitializer {
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
        Identifier.fromNamespaceAndPath("dynamictrident", "general")
    );

    public static final KeyMapping TOGGLE_KEY = KeyMappingHelper.registerKeyMapping(
        new KeyMapping(
            "key.dynamictrident.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X,
            CATEGORY
        )
    );

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE_KEY.consumeClick()) {
                if (client.player != null) {
                    ItemStack mainHand = client.player.getMainHandItem();
                    ItemStack offHand = client.player.getOffhandItem();
                    if (mainHand.is(Items.TRIDENT) || offHand.is(Items.TRIDENT)) {
                        // Send packet to server to toggle the mode
                        ClientPlayNetworking.send(new DynamicTrident.TridentTogglePayload());
                    }
                }
            }
        });
    }
}
