package dev.underline.mccommand.bridge.client.mixin;

import dev.underline.mccommand.bridge.client.ClientChatEventStore;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Inject(method = "addMessageToQueue", at = @At("TAIL"))
    private void mcCommand$captureMessage(GuiMessage message, CallbackInfo callback) {
        ClientChatEventStore.add(
                message.addedTime(),
                message.content().getString(),
                message.source().name().toLowerCase(java.util.Locale.ROOT)
        );
    }
}
