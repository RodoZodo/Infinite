package rodozodo.infinite;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import org.jetbrains.annotations.Nullable;

public final class FillCommandTree {
    public enum Action { NONE, START, PAUSE, STOP }

    @FunctionalInterface
    public interface Executor<S> {
        int run(CommandContext<S> ctx, @Nullable Integer radius, @Nullable Integer x, @Nullable Integer z, Action action);
    }

    private FillCommandTree() {}

    public static <S> LiteralArgumentBuilder<S> build(String name, Executor<S> executor) {
        var root = LiteralArgumentBuilder.<S>literal(name);
        addNumbers(root, Action.NONE, executor);
        for (var action : new Action[]{Action.START, Action.PAUSE, Action.STOP}) {
            var actionNode = LiteralArgumentBuilder.<S>literal(actionName(action))
                    .executes(ctx -> executor.run(ctx, null, null, null, action));
            addNumbers(actionNode, action, executor);
            root.then(actionNode);
        }
        return root;
    }

    private static <S> void addNumbers(LiteralArgumentBuilder<S> parent, Action fixedAction, Executor<S> executor) {
        var first = RequiredArgumentBuilder.<S, Integer>argument("radius_or_x", IntegerArgumentType.integer())
                .executes(ctx -> executor.run(ctx, intArg(ctx, "radius_or_x"), null, null, fixedAction));
        if (fixedAction == Action.NONE) {
            attachTrailingActions(first, (ctx, action) ->
                    executor.run(ctx, intArg(ctx, "radius_or_x"), null, null, action));
        }

        var second = RequiredArgumentBuilder.<S, Integer>argument("z_or_x", IntegerArgumentType.integer())
                .executes(ctx -> executor.run(ctx, null, intArg(ctx, "radius_or_x"), intArg(ctx, "z_or_x"), fixedAction));
        if (fixedAction == Action.NONE) {
            attachTrailingActions(second, (ctx, action) ->
                    executor.run(ctx, null, intArg(ctx, "radius_or_x"), intArg(ctx, "z_or_x"), action));
        }

        var third = RequiredArgumentBuilder.<S, Integer>argument("z", IntegerArgumentType.integer())
                .executes(ctx -> executor.run(ctx, intArg(ctx, "radius_or_x"), intArg(ctx, "z_or_x"), intArg(ctx, "z"),
                        fixedAction));
        if (fixedAction == Action.NONE) {
            attachTrailingActions(third, (ctx, action) ->
                    executor.run(ctx, intArg(ctx, "radius_or_x"), intArg(ctx, "z_or_x"), intArg(ctx, "z"), action));
        }

        second.then(third);
        first.then(second);
        parent.then(first);
    }

    @FunctionalInterface
    private interface TrailingExecutor<S> {
        int run(CommandContext<S> ctx, Action action);
    }

    private static <S> void attachTrailingActions(RequiredArgumentBuilder<S, Integer> node,
                                                      TrailingExecutor<S> executor) {
        for (var action : new Action[]{Action.START, Action.PAUSE, Action.STOP}) {
            node.then(LiteralArgumentBuilder.<S>literal(actionName(action))
                    .executes(ctx -> executor.run(ctx, action)));
        }
    }

    private static int intArg(CommandContext<?> ctx, String name) {
        return IntegerArgumentType.getInteger(ctx, name);
    }

    private static String actionName(Action action) {
        return switch (action) {
            case NONE -> "";
            case START -> "start";
            case PAUSE -> "pause";
            case STOP -> "stop";
        };
    }
}
