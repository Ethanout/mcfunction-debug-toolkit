package dev.underline.mccommand.bridge.debug;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.ContextChain;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.execution.ChainModifiers;
import net.minecraft.commands.execution.CustomCommandExecutor;
import net.minecraft.commands.execution.ExecutionControl;

public final class DebugInternalCommand implements CustomCommandExecutor.CommandAdapter<CommandSourceStack> {
    private DebugInternalCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("mcdebug_internal")
                        .then(Commands.literal("emit")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(new DebugInternalCommand())))));
    }

    @Override
    public void run(
            CommandSourceStack source,
            ContextChain<CommandSourceStack> context,
            ChainModifiers modifiers,
            ExecutionControl<CommandSourceStack> control
    ) {
        String id = StringArgumentType.getString(context.getTopContext(), "id");
        try {
            DebugDirectiveExecutor.execute(source, id, control.currentFrame().depth());
        } finally {
            // Debug output must never stop the containing function.
            source.callback().onSuccess(1);
        }
    }
}
