package com.dynamictrident;

import com.mojang.serialization.Codec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.core.Registry;
import net.minecraft.server.level.ServerPlayer;

public class DynamicTrident implements ModInitializer {
    public static final String MOD_ID = "dynamictrident";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Custom Data Components
    public static final DataComponentType<String> TRIDENT_MODE = Registry.register(
        BuiltInRegistries.DATA_COMPONENT_TYPE,
        Identifier.fromNamespaceAndPath(MOD_ID, "trident_mode"),
        DataComponentType.<String>builder()
            .persistent(Codec.STRING)
            .build()
    );

    public static final DataComponentType<ItemEnchantments> STORED_ENCHANTMENTS = Registry.register(
        BuiltInRegistries.DATA_COMPONENT_TYPE,
        Identifier.fromNamespaceAndPath(MOD_ID, "stored_enchantments"),
        DataComponentType.<ItemEnchantments>builder()
            .persistent(ItemEnchantments.CODEC)
            .build()
    );

    // Networking Payload record
    public record TridentTogglePayload() implements CustomPacketPayload {
        public static final Type<TridentTogglePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MOD_ID, "trident_toggle"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TridentTogglePayload> CODEC = StreamCodec.unit(new TridentTogglePayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @Override
    public void onInitialize() {
        LOGGER.info("DynamicTrident Initializing...");

        // Register custom network payload using 26.2 style API
        PayloadTypeRegistry.serverboundPlay().register(TridentTogglePayload.TYPE, TridentTogglePayload.CODEC);

        // Register packet receiver on the server side
        ServerPlayNetworking.registerGlobalReceiver(TridentTogglePayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                ItemStack stack = player.getMainHandItem();
                if (!stack.is(Items.TRIDENT)) {
                    stack = player.getOffhandItem();
                }
                if (stack.is(Items.TRIDENT)) {
                    toggleTridentMode(player, stack);
                }
            });
        });

        LOGGER.info("DynamicTrident Initialized!");
    }

    private void toggleTridentMode(ServerPlayer player, ItemStack stack) {
        // Copy the stack so we don't modify it in-place, which prevents server inventory diff comparison
        ItemStack newStack = stack.copy();

        // Read current mode: default is throwing
        String currentMode = newStack.getOrDefault(TRIDENT_MODE, "throwing");
        String newMode = currentMode.equals("riptide") ? "throwing" : "riptide";

        // Get enchantment registry lookup using HolderLookup
        HolderLookup.Provider provider = player.registryAccess();
        HolderLookup.RegistryLookup<Enchantment> enchantmentLookup = provider.lookupOrThrow(Registries.ENCHANTMENT);
        
        Holder<Enchantment> riptideHolder = enchantmentLookup.getOrThrow(Enchantments.RIPTIDE);
        Holder<Enchantment> loyaltyHolder = enchantmentLookup.getOrThrow(Enchantments.LOYALTY);
        Holder<Enchantment> channelingHolder = enchantmentLookup.getOrThrow(Enchantments.CHANNELING);

        // Get current active & stored enchantments
        ItemEnchantments activeEnchants = newStack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        ItemEnchantments storedEnchants = newStack.getOrDefault(STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);

        ItemEnchantments.Mutable newActive = new ItemEnchantments.Mutable(activeEnchants);
        ItemEnchantments.Mutable newStored = new ItemEnchantments.Mutable(storedEnchants);

        if (newMode.equals("riptide")) {
            // Swap Loyalty/Channeling out to stored, and move Riptide into active
            int loyaltyLevel = activeEnchants.getLevel(loyaltyHolder);
            int channelingLevel = activeEnchants.getLevel(channelingHolder);
            int riptideLevel = storedEnchants.getLevel(riptideHolder);

            // If active has Loyalty/Channeling, store them
            if (loyaltyLevel > 0) {
                newStored.set(loyaltyHolder, loyaltyLevel);
                newActive.set(loyaltyHolder, 0);
            }
            if (channelingLevel > 0) {
                newStored.set(channelingHolder, channelingLevel);
                newActive.set(channelingHolder, 0);
            }

            // Restore Riptide if present in stored
            if (riptideLevel > 0) {
                newActive.set(riptideHolder, riptideLevel);
                newStored.set(riptideHolder, 0);
            }

            // Apply components
            newStack.set(TRIDENT_MODE, "riptide");
            newStack.set(DataComponents.ENCHANTMENTS, newActive.toImmutable());
            newStack.set(STORED_ENCHANTMENTS, newStored.toImmutable());

            // Play Riptide switch sound & feedback
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(), 
                SoundEvents.TRIDENT_RETURN, SoundSource.PLAYERS, 1.0F, 1.2F);
            player.sendSystemMessage(
                Component.literal("⚔ Riptide Mode ⚔").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), 
                true
            );
        } else {
            // Swap Riptide out to stored, and restore Loyalty/Channeling from stored
            int riptideLevel = activeEnchants.getLevel(riptideHolder);
            int loyaltyLevel = storedEnchants.getLevel(loyaltyHolder);
            int channelingLevel = storedEnchants.getLevel(channelingHolder);

            // Store active Riptide
            if (riptideLevel > 0) {
                newStored.set(riptideHolder, riptideLevel);
                newActive.set(riptideHolder, 0);
            }

            // Restore Loyalty/Channeling
            if (loyaltyLevel > 0) {
                newActive.set(loyaltyHolder, loyaltyLevel);
                newStored.set(loyaltyHolder, 0);
            }
            if (channelingLevel > 0) {
                newActive.set(channelingHolder, channelingLevel);
                newStored.set(channelingHolder, 0);
            }

            // Apply components
            newStack.set(TRIDENT_MODE, "throwing");
            newStack.set(DataComponents.ENCHANTMENTS, newActive.toImmutable());
            newStack.set(STORED_ENCHANTMENTS, newStored.toImmutable());

            // Play Throwing switch sound & feedback
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(), 
                SoundEvents.TRIDENT_THROW, SoundSource.PLAYERS, 1.0F, 0.8F);
            player.sendSystemMessage(
                Component.literal("⚡ Throwing Mode ⚡").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), 
                true
            );
        }

        // Set the modified copy back to player hand to trigger packet sync
        if (player.getMainHandItem() == stack) {
            player.setItemInHand(InteractionHand.MAIN_HAND, newStack);
        } else {
            player.setItemInHand(InteractionHand.OFF_HAND, newStack);
        }
    }
}
