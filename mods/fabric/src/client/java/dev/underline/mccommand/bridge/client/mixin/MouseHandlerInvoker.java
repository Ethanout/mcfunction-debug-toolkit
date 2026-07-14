package dev.underline.mccommand.bridge.client.mixin;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MouseHandler.class)
public interface MouseHandlerInvoker {
    @Invoker("onMove")
    void mcCommandInvokeOnMove(long window, double x, double y);

    @Invoker("onButton")
    void mcCommandInvokeOnButton(long window, MouseButtonInfo button, int action);
}
