package dev.underline.mccommand.bridge.mixin;

import com.mojang.brigadier.CommandDispatcher;
import dev.underline.mccommand.bridge.debug.DebugFunctionPreprocessor;
import dev.underline.mccommand.bridge.debug.DebugMixinGuard;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(CommandFunction.class)
public interface CommandFunctionMixin {
    @Inject(method = "fromLines", at = @At("HEAD"), cancellable = true)
    private static <T extends ExecutionCommandSource<T>> void mcCommandBridge$preprocessDebugDirectives(
            Identifier id,
            CommandDispatcher<T> dispatcher,
            T source,
            List<String> lines,
            CallbackInfoReturnable<CommandFunction<T>> callback
    ) {
        if (DebugMixinGuard.active()) return;
        DebugMixinGuard.enter();
        try {
            List<String> transformed = DebugFunctionPreprocessor.preprocess(id, lines);
            callback.setReturnValue(CommandFunction.fromLines(id, dispatcher, source, transformed));
        } finally {
            DebugMixinGuard.exit();
        }
    }
}
