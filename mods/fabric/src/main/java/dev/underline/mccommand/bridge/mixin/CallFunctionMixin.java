package dev.underline.mccommand.bridge.mixin;

import dev.underline.mccommand.bridge.debug.DebugFunctionStack;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.execution.Frame;
import net.minecraft.commands.execution.tasks.CallFunction;
import net.minecraft.commands.functions.InstantiatedFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CallFunction.class)
public abstract class CallFunctionMixin<T extends ExecutionCommandSource<T>> {
    @Shadow
    @Final
    private InstantiatedFunction<T> function;

    @Inject(method = "execute", at = @At("HEAD"))
    private void mcCommandBridge$recordFunctionFrame(
            T source,
            ExecutionContext<T> context,
            Frame frame,
            CallbackInfo callback
    ) {
        DebugFunctionStack.enter(frame.depth() + 1, function.id());
    }
}
