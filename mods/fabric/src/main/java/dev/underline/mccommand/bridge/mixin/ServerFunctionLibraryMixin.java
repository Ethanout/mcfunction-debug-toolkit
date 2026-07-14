package dev.underline.mccommand.bridge.mixin;

import dev.underline.mccommand.bridge.debug.DebugDirectiveRegistry;
import net.minecraft.server.ServerFunctionLibrary;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(ServerFunctionLibrary.class)
public abstract class ServerFunctionLibraryMixin {
    @Unique
    private static final ThreadLocal<Long> MC_COMMAND_BRIDGE_RELOAD = new ThreadLocal<>();

    @Inject(method = "reload", at = @At("HEAD"))
    private void mcCommandBridge$beginDebugDirectiveReload(
            PreparableReloadListener.SharedState sharedState,
            Executor preparationExecutor,
            PreparableReloadListener.PreparationBarrier preparationBarrier,
            Executor applicationExecutor,
            CallbackInfoReturnable<CompletableFuture<Void>> callback
    ) {
        MC_COMMAND_BRIDGE_RELOAD.set(DebugDirectiveRegistry.beginReload());
    }

    @Inject(method = "reload", at = @At("RETURN"))
    private void mcCommandBridge$finishDebugDirectiveReload(
            PreparableReloadListener.SharedState sharedState,
            Executor preparationExecutor,
            PreparableReloadListener.PreparationBarrier preparationBarrier,
            Executor applicationExecutor,
            CallbackInfoReturnable<CompletableFuture<Void>> callback
    ) {
        Long generation = MC_COMMAND_BRIDGE_RELOAD.get();
        MC_COMMAND_BRIDGE_RELOAD.remove();
        if (generation == null) return;
        callback.getReturnValue().whenComplete((unused, error) ->
                DebugDirectiveRegistry.finishReload(generation, error == null));
    }
}
