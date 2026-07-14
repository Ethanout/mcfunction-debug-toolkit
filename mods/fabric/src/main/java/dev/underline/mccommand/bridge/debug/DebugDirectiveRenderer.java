package dev.underline.mccommand.bridge.debug;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.underline.mccommand.bridge.debug.DebugAst.CompositePart;
import dev.underline.mccommand.bridge.debug.DebugAst.ContextKey;
import dev.underline.mccommand.bridge.debug.DebugAst.ContextQuery;
import dev.underline.mccommand.bridge.debug.DebugAst.DataQuery;
import dev.underline.mccommand.bridge.debug.DebugAst.ExpressionPart;
import dev.underline.mccommand.bridge.debug.DebugAst.FieldPart;
import dev.underline.mccommand.bridge.debug.DebugAst.Format;
import dev.underline.mccommand.bridge.debug.DebugAst.FormatPart;
import dev.underline.mccommand.bridge.debug.DebugAst.FormatText;
import dev.underline.mccommand.bridge.debug.DebugAst.NestedListPart;
import dev.underline.mccommand.bridge.debug.DebugAst.NumericFormat;
import dev.underline.mccommand.bridge.debug.DebugAst.ScoreQuery;
import dev.underline.mccommand.bridge.debug.DebugAst.SelectorQuery;
import dev.underline.mccommand.bridge.debug.DebugAst.TemplatePart;
import dev.underline.mccommand.bridge.debug.DebugAst.TextPart;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.commands.arguments.ScoreHolderArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.util.ProblemReporter;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DebugDirectiveRenderer {
    private static final int MAX_LEAVES = 256;
    private static final int MAX_SOURCE_VISITS = 512;
    private static final int MAX_NBT_BYTES = 1_048_576;
    private static final int MAX_CHARACTERS = 32_000;
    private static final String TRUNCATION_MARKER = "…[truncated]";

    Rendered render(CommandSourceStack source, DebugAst.Directive directive, int frameDepth)
            throws DebugRuntimeException {
        List<Identifier> stack = DebugFunctionStack.throughDepth(frameDepth);
        RenderBudget budget = new RenderBudget(MAX_LEAVES, MAX_SOURCE_VISITS, MAX_NBT_BYTES);
        MutableComponent output = Component.empty();
        for (TemplatePart part : directive.parts()) {
            if (part instanceof TextPart text) {
                output.append(text.text());
            } else if (part instanceof ExpressionPart expression) {
                List<RenderItem> items = resolve(source, directive, expression, stack, budget);
                output.append(renderExpression(items, expression.format()));
            }
            if (output.getString().length() > MAX_CHARACTERS) {
                return new Rendered(truncateWithMarker(output, MAX_CHARACTERS), true, stack);
            }
        }
        if (budget.truncated()) {
            return new Rendered(truncateWithMarker(output, MAX_CHARACTERS), true, stack);
        }
        return new Rendered(output, false, stack);
    }

    private List<RenderItem> resolve(
            CommandSourceStack source,
            DebugAst.Directive directive,
            ExpressionPart expression,
            List<Identifier> stack,
            RenderBudget budget
    ) throws DebugRuntimeException {
        if (expression.query() instanceof ContextQuery context) {
            return List.of(contextItem(source, directive, context.key(), stack));
        }
        if (expression.query() instanceof SelectorQuery selector) {
            return List.of(selectorItem(source, selector, budget));
        }
        if (expression.query() instanceof ScoreQuery score) {
            return scoreItems(source, score, budget);
        }
        if (expression.query() instanceof DataQuery data) {
            return dataItems(source, data, expression.format(), budget);
        }
        throw new DebugRuntimeException("unknown_query", "unknown directive query");
    }

    private RenderItem selectorItem(
            CommandSourceStack source,
            SelectorQuery query,
            RenderBudget budget
    ) throws DebugRuntimeException {
        final List<? extends Entity> entities;
        try {
            StringReader reader = new StringReader(query.selector());
            EntitySelector selector = EntityArgument.entities().parse(reader);
            if (reader.canRead()) {
                throw new DebugRuntimeException(
                        "invalid_entity_selector", "trailing input in entity selector at " + reader.getCursor());
            }
            if (selector.getMaxResults() > MAX_SOURCE_VISITS) {
                selector = EntityArgument.entities().parse(new StringReader(
                        capEntitySelector(query.selector(), MAX_SOURCE_VISITS + 1)));
            }
            entities = selector.findEntities(source);
        } catch (CommandSyntaxException error) {
            throw new DebugRuntimeException("invalid_entity_selector", error.getMessage());
        }

        List<Entity> bounded = new ArrayList<>();
        for (Entity entity : entities) {
            if (!budget.tryVisitSource() || !budget.tryConsume()) break;
            bounded.add(entity);
        }
        return RenderItem.single(stringValue(EntitySelector.joinNames(bounded)));
    }

    private RenderItem contextItem(
            CommandSourceStack source,
            DebugAst.Directive directive,
            ContextKey key,
            List<Identifier> stack
    ) {
        return switch (key) {
            case NAME -> RenderItem.single(stringValue(source.getDisplayName()));
            case DIMENSION -> RenderItem.single(dimension(source));
            case POSITION -> numericContext(List.of(
                    source.getPosition().x, source.getPosition().y, source.getPosition().z));
            case ROTATION -> numericContext(List.of(
                    source.getRotation().y, source.getRotation().x));
            case ANCHOR -> RenderItem.single(identifierValue(
                    source.getAnchor() == EntityAnchorArgument.Anchor.EYES ? "eyes" : "feet"));
            case FUNCTION_NAME -> RenderItem.single(identifierValue(
                    stack.isEmpty() ? directive.functionId() : stack.getLast().toString()));
            case FUNCTION_STACK -> RenderItem.single(joinedIdentifiers(stack.isEmpty()
                    ? List.of(Identifier.parse(directive.functionId())) : stack));
        };
    }

    private static RenderItem numericContext(List<? extends Number> values) {
        MutableComponent combined = Component.empty();
        List<RenderItem> children = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            Number number = values.get(index);
            Component component = numberValue(number.toString());
            if (index > 0) combined.append(" ");
            combined.append(component);
            RenderValue value = new RenderValue(component, number);
            children.add(new RenderItem(Map.of("", value, "value", value), component, List.of()));
        }
        RenderValue value = new RenderValue(combined, null);
        return new RenderItem(Map.of("", value, "value", value), combined, List.copyOf(children));
    }

    private static Component dimension(CommandSourceStack source) {
        Identifier id = source.getLevel().dimension().identifier();
        return Component.translatableWithFallback(id.toLanguageKey("dimension"), id.toString())
                .withStyle(ChatFormatting.AQUA)
                .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(identifierValue(id.toString()))));
    }

    private static Component joinedIdentifiers(List<Identifier> values) {
        MutableComponent result = Component.empty();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) result.append(Component.literal(" -> ").withStyle(ChatFormatting.GRAY));
            result.append(identifierValue(values.get(index).toString()));
        }
        return result;
    }

    private List<RenderItem> scoreItems(CommandSourceStack source, ScoreQuery query, RenderBudget budget)
            throws DebugRuntimeException {
        Scoreboard scoreboard = source.getServer().getScoreboard();
        Objective objective = scoreboard.getObjective(query.objective());
        if (objective == null) {
            throw new DebugRuntimeException("unknown_objective", "unknown objective '" + query.objective() + "'");
        }

        final Iterable<ScoreHolder> holders;
        try {
            var result = ScoreHolderArgument.scoreHolders().parse(new StringReader(query.holder()));
            holders = result.getNames(source, scoreboard::getTrackedPlayers);
        } catch (CommandSyntaxException error) {
            throw new DebugRuntimeException("invalid_score_holder", error.getMessage());
        }

        List<RenderItem> items = new ArrayList<>();
        for (ScoreHolder holder : holders) {
            if (!budget.tryVisitSource()) break;
            ReadOnlyScoreInfo info = scoreboard.getPlayerScoreInfo(holder, objective);
            if (info == null) continue;
            if (!budget.tryConsume()) break;
            String holderName = holder.getScoreboardName();
            Component nativeDisplayName = holder.getDisplayName();
            Component displayName = stringValue(nativeDisplayName == null
                    ? Component.literal(holderName)
                    : nativeDisplayName).copy().withStyle(style ->
                    style.withHoverEvent(new HoverEvent.ShowText(stringValue(Component.literal(holderName)))));
            Component score = numberValue(Integer.toString(info.value()));
            MutableComponent defaultMulti = Component.empty().append(displayName).append(": ").append(score);
            Map<String, RenderValue> fields = new LinkedHashMap<>();
            fields.put("", new RenderValue(score, info.value()));
            fields.put("score", new RenderValue(score, info.value()));
            fields.put("name", new RenderValue(displayName, null));
            fields.put("display_name", new RenderValue(displayName, null));
            fields.put("holder", new RenderValue(stringValue(Component.literal(holderName)), null));
            items.add(new RenderItem(Map.copyOf(fields), defaultMulti, List.of()));
        }
        return List.copyOf(items);
    }

    private List<RenderItem> dataItems(
            CommandSourceStack source,
            DataQuery query,
            Format format,
            RenderBudget budget
    )
            throws DebugRuntimeException {
        final NbtPathArgument.NbtPath path;
        try {
            StringReader reader = new StringReader(query.path());
            path = NbtPathArgument.nbtPath().parse(reader);
            if (reader.canRead()) {
                throw new DebugRuntimeException("invalid_nbt_path", "trailing input in NBT path at " + reader.getCursor());
            }
        } catch (CommandSyntaxException error) {
            throw new DebugRuntimeException("invalid_nbt_path", error.getMessage());
        }

        List<DataGroup> groups = switch (query.source()) {
            case STORAGE -> storageGroups(source, query, path, budget);
            case ENTITY -> entityGroups(source, query, path, budget);
            case BLOCK -> blockGroups(source, query, path, budget);
        };
        boolean hierarchical = format != null && containsNestedList(format.parts());
        if (hierarchical) {
            return groups.stream().map(DataGroup::asRenderItem).toList();
        }
        List<RenderItem> leaves = new ArrayList<>();
        for (DataGroup group : groups) leaves.addAll(group.children());
        return List.copyOf(leaves);
    }

    private List<DataGroup> storageGroups(
            CommandSourceStack source,
            DataQuery query,
            NbtPathArgument.NbtPath path,
            RenderBudget budget
    ) throws DebugRuntimeException {
        Identifier id;
        try {
            id = Identifier.parse(query.target());
        } catch (RuntimeException error) {
            throw new DebugRuntimeException("invalid_storage_id", "invalid storage ID '" + query.target() + "'");
        }
        CompoundTag root = source.getServer().getCommandStorage().get(id);
        if (!budget.tryVisitSource()) return List.of();
        DataGroup group = dataGroup(identifierValue(id.toString()), 0, valuesAt(path, root), budget);
        return group.children().isEmpty() ? List.of() : List.of(group);
    }

    private List<DataGroup> entityGroups(
            CommandSourceStack source,
            DataQuery query,
            NbtPathArgument.NbtPath path,
            RenderBudget budget
    ) throws DebugRuntimeException {
        final List<? extends Entity> entities;
        try {
            StringReader reader = new StringReader(query.target());
            EntitySelector selector = EntityArgument.entities().parse(reader);
            if (reader.canRead()) {
                throw new DebugRuntimeException("invalid_entity_selector", "trailing input in entity selector at " + reader.getCursor());
            }
            if (selector.getMaxResults() > MAX_SOURCE_VISITS) {
                String capped = capEntitySelector(query.target(), MAX_SOURCE_VISITS + 1);
                selector = EntityArgument.entities().parse(new StringReader(capped));
            }
            entities = selector.findEntities(source);
        } catch (CommandSyntaxException error) {
            throw new DebugRuntimeException("invalid_entity_selector", error.getMessage());
        }

        List<DataGroup> groups = new ArrayList<>();
        int globalIndex = 0;
        for (int entityIndex = 0; entityIndex < entities.size(); entityIndex++) {
            if (!budget.tryVisitSource()) break;
            Entity entity = entities.get(entityIndex);
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, source.registryAccess());
            entity.saveWithoutId(output);
            CompoundTag root = output.buildResult();
            if (!budget.tryConsumeNbtBytes(root.sizeInBytes())) break;
            List<Tag> values = valuesAt(path, root);
            Component label = stringValue(entity.getDisplayName());
            DataGroup group = dataGroup(label, entityIndex, globalIndex, values, budget);
            if (!group.children().isEmpty()) groups.add(group);
            globalIndex += group.children().size();
            if (budget.truncated()) break;
        }
        return List.copyOf(groups);
    }

    static String capEntitySelector(String source, int maximum) {
        int open = source.indexOf('[');
        if (open < 0) return source + "[limit=" + maximum + "]";
        int close = source.lastIndexOf(']');
        if (close <= open) return source;

        int segmentStart = open + 1;
        int nested = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = segmentStart; index <= close; index++) {
            char current = index == close ? ',' : source.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (quote != 0) {
                if (current == quote) quote = 0;
                continue;
            }
            if (current == '"' || current == '\'') {
                quote = current;
                continue;
            }
            if (current == '{' || current == '[' || current == '(') {
                nested++;
                continue;
            }
            if (current == '}' || current == ']' || current == ')') {
                if (nested > 0) nested--;
                continue;
            }
            if (current == ',' && nested == 0) {
                String segment = source.substring(segmentStart, index);
                int equals = segment.indexOf('=');
                if (equals >= 0 && segment.substring(0, equals).trim().equals("limit")) {
                    int valueStart = segmentStart + equals + 1;
                    return source.substring(0, valueStart) + maximum + source.substring(index);
                }
                segmentStart = index + 1;
            }
        }

        String separator = close == open + 1 ? "" : ",";
        return source.substring(0, close) + separator + "limit=" + maximum + source.substring(close);
    }

    private List<DataGroup> blockGroups(
            CommandSourceStack source,
            DataQuery query,
            NbtPathArgument.NbtPath path,
            RenderBudget budget
    ) throws DebugRuntimeException {
        String coordinates = String.join(" ", query.coordinates());
        final BlockPos position;
        try {
            StringReader reader = new StringReader(coordinates);
            var parsed = BlockPosArgument.blockPos().parse(reader);
            if (reader.canRead()) {
                throw new DebugRuntimeException("invalid_block_position", "trailing input in block position at " + reader.getCursor());
            }
            position = parsed.getBlockPos(source);
        } catch (CommandSyntaxException error) {
            throw new DebugRuntimeException("invalid_block_position", error.getMessage());
        }
        if (!source.getLevel().isLoaded(position)) {
            return List.of();
        }
        BlockEntity blockEntity = source.getLevel().getBlockEntity(position);
        if (blockEntity == null) {
            return List.of();
        }
        if (!budget.tryVisitSource()) return List.of();
        CompoundTag root = blockEntity.saveWithFullMetadata(source.registryAccess());
        if (!budget.tryConsumeNbtBytes(root.sizeInBytes())) return List.of();
        DataGroup group = dataGroup(
                numberValue(position.toShortString()), 0, valuesAt(path, root), budget);
        return group.children().isEmpty() ? List.of() : List.of(group);
    }

    static List<Tag> valuesAt(NbtPathArgument.NbtPath path, Tag root) {
        try {
            return List.copyOf(path.get(root));
        } catch (CommandSyntaxException error) {
            return List.of();
        }
    }

    private static DataGroup dataGroup(
            Component label,
            int sourceIndex,
            List<Tag> values,
            RenderBudget budget
    ) {
        return dataGroup(label, sourceIndex, 0, values, budget);
    }

    private static DataGroup dataGroup(
            Component label,
            int sourceIndex,
            int globalStart,
            List<Tag> values,
            RenderBudget budget
    ) {
        List<RenderItem> children = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            if (!budget.tryConsume()) break;
            Tag tag = values.get(index);
            if (!budget.tryConsumeNbtBytes(tag.sizeInBytes())) break;
            Component value = NbtUtils.toPrettyComponent(tag);
            Number number = tag.asNumber().orElse(null);
            Map<String, RenderValue> fields = new LinkedHashMap<>();
            fields.put("", new RenderValue(value, number));
            fields.put("value", new RenderValue(value, number));
            fields.put("entity", new RenderValue(label, null));
            fields.put("index", new RenderValue(numberValue(Integer.toString(index)), index));
            fields.put("entity_index", new RenderValue(numberValue(Integer.toString(sourceIndex)), sourceIndex));
            fields.put("global_index", new RenderValue(numberValue(Integer.toString(globalStart + index)), globalStart + index));
            children.add(new RenderItem(Map.copyOf(fields), value, List.of()));
        }
        return new DataGroup(label, sourceIndex, globalStart, List.copyOf(children));
    }

    private static boolean containsNestedList(List<FormatPart> parts) {
        for (FormatPart part : parts) {
            if (part instanceof NestedListPart) return true;
            if (part instanceof CompositePart composite && containsNestedList(composite.parts())) return true;
        }
        return false;
    }

    private Component renderExpression(List<RenderItem> items, Format format) throws DebugRuntimeException {
        if (format == null) {
            if (items.isEmpty()) return Component.empty();
            if (items.size() == 1) return items.getFirst().field("").component();
            MutableComponent result = Component.empty();
            for (int index = 0; index < items.size(); index++) {
                if (index > 0) result.append(", ");
                result.append(items.get(index).defaultMultiple());
            }
            return result;
        }
        if (!format.repeating() && items.size() > 1) {
            throw new DebugRuntimeException(
                    "expected_one_value",
                    "expected one value, got " + items.size());
        }
        if (items.isEmpty()) return Component.empty();
        if (format.directNumericFormat() != null) {
            if (items.size() > 1) {
                throw new DebugRuntimeException("expected_one_value", "expected one value, got " + items.size());
            }
            RenderItem item = items.getFirst();
            if (item.children().isEmpty()) return formatted(item.field(""), format.directNumericFormat());
            MutableComponent result = Component.empty();
            for (int index = 0; index < item.children().size(); index++) {
                if (index > 0) result.append(" ");
                result.append(formatted(item.children().get(index).field(""), format.directNumericFormat()));
            }
            return result;
        }

        ItemCursor cursor = new ItemCursor(items);
        MutableComponent result = Component.empty();
        if (!format.repeating()) {
            renderParts(format.parts(), cursor, result, format.strip(), false);
            return result;
        }

        while (cursor.hasNext()) {
            int before = cursor.position();
            renderParts(format.parts(), cursor, result, format.strip(), true);
            if (cursor.position() == before) {
                throw new DebugRuntimeException("repeat_without_value", "repeat pattern does not contain an item placeholder");
            }
        }
        return result;
    }

    private boolean renderParts(
            List<FormatPart> parts,
            ItemCursor cursor,
            MutableComponent output,
            boolean strip,
            boolean stopAfterLast
    ) throws DebugRuntimeException {
        for (FormatPart part : parts) {
            if (part instanceof FormatText text) {
                output.append(text.text());
                continue;
            }
            if (part instanceof FieldPart field) {
                if (!cursor.hasNext()) {
                    if (strip) return true;
                    output.append(field.raw());
                    continue;
                }
                RenderItem item = cursor.next();
                output.append(formatted(item.field(field.field()), field.numericFormat()));
                if (strip && stopAfterLast && !cursor.hasNext()) return true;
                continue;
            }
            if (part instanceof CompositePart composite) {
                if (!cursor.hasNext()) {
                    if (strip) return true;
                    output.append(composite.raw());
                    continue;
                }
                RenderItem item = cursor.next();
                renderComposite(composite.parts(), item, output);
                if (strip && stopAfterLast && !cursor.hasNext()) return true;
                continue;
            }
            if (part instanceof NestedListPart nested) {
                if (!cursor.hasNext()) {
                    if (strip) return true;
                    output.append(nested.raw());
                    continue;
                }
                RenderItem item = cursor.next();
                output.append(renderExpression(item.children(), nested.format()));
                if (strip && stopAfterLast && !cursor.hasNext()) return true;
            }
        }
        return false;
    }

    private void renderComposite(List<FormatPart> parts, RenderItem item, MutableComponent output)
            throws DebugRuntimeException {
        for (FormatPart part : parts) {
            if (part instanceof FormatText text) {
                output.append(text.text());
            } else if (part instanceof FieldPart field) {
                output.append(formatted(item.field(field.field()), field.numericFormat()));
            } else if (part instanceof NestedListPart nested) {
                output.append(renderExpression(item.children(), nested.format()));
            } else if (part instanceof CompositePart nestedComposite) {
                renderComposite(nestedComposite.parts(), item, output);
            }
        }
    }

    private static Component formatted(RenderValue value, NumericFormat format) {
        if (format == null || value.number() == null) return value.component();
        String formatted = formatNumber(value.number(), format);
        if (formatted == null) return value.component();
        return formattedNumberValue(formatted, value.component());
    }

    static Component formattedNumberValue(String formatted, Component source) {
        MutableComponent result = Component.literal(formatted).withStyle(source.getStyle());
        return result.getStyle().getColor() == null ? result.withStyle(ChatFormatting.GOLD) : result;
    }

    static Component numberValue(String value) {
        return Component.literal(value).withStyle(ChatFormatting.GOLD);
    }

    static Component identifierValue(String value) {
        return Component.literal(value).withStyle(ChatFormatting.AQUA);
    }

    static Component stringValue(Component value) {
        MutableComponent copy = value.copy();
        return copy.getStyle().getColor() == null ? copy.withStyle(ChatFormatting.GREEN) : copy;
    }

    static String formatNumber(Number number, NumericFormat format) {
        String value;
        int precision = format.precision();
        switch (format.type()) {
            case 'd' -> {
                Long integer = exactLong(number);
                if (integer == null) return null;
                value = Long.toString(integer);
            }
            case 'f' -> value = String.format(Locale.ROOT, "%." + Math.max(0, precision < 0 ? 6 : precision) + "f", number.doubleValue());
            case 'e' -> value = String.format(Locale.ROOT, "%." + Math.max(0, precision < 0 ? 6 : precision) + "e", number.doubleValue());
            case 'g' -> value = String.format(Locale.ROOT, "%." + Math.max(1, precision < 0 ? 6 : precision) + "g", number.doubleValue());
            case 'p' -> {
                int digits = Math.max(1, precision < 0 ? 6 : precision);
                double numeric = number.doubleValue();
                value = Double.isFinite(numeric)
                        ? decimalValue(number)
                                .round(new MathContext(digits, RoundingMode.HALF_UP))
                                .stripTrailingZeros()
                                .toPlainString()
                        : Double.toString(numeric);
            }
            default -> value = number.toString();
        }
        if (format.width() <= value.length()) return value;
        int padding = format.width() - value.length();
        char fill = format.zeroPad() ? '0' : ' ';
        return switch (format.align() == null ? (format.zeroPad() ? '=' : '>') : format.align()) {
            case '<' -> value + String.valueOf(fill).repeat(padding);
            case '^' -> String.valueOf(fill).repeat(padding / 2) + value
                    + String.valueOf(fill).repeat(padding - padding / 2);
            case '=' -> padAfterSign(value, fill, padding);
            case '>' -> fill == '0' ? padAfterSign(value, fill, padding)
                    : String.valueOf(fill).repeat(padding) + value;
            default -> String.valueOf(fill).repeat(padding) + value;
        };
    }

    private static Long exactLong(Number number) {
        if (number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long) {
            return number.longValue();
        }
        try {
            double numeric = number.doubleValue();
            if (!Double.isFinite(numeric)) return null;
            return new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            return null;
        }
    }

    private static BigDecimal decimalValue(Number number) {
        if (number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long) {
            return BigDecimal.valueOf(number.longValue());
        }
        return BigDecimal.valueOf(number.doubleValue());
    }

    private static String padAfterSign(String value, char fill, int padding) {
        if (!value.isEmpty() && (value.charAt(0) == '-' || value.charAt(0) == '+')) {
            return value.charAt(0) + String.valueOf(fill).repeat(padding) + value.substring(1);
        }
        return String.valueOf(fill).repeat(padding) + value;
    }

    static Component truncateWithMarker(Component component, int maximumCharacters) {
        if (maximumCharacters < TRUNCATION_MARKER.length()) {
            return Component.literal(TRUNCATION_MARKER.substring(0, Math.max(0, maximumCharacters)));
        }
        int remaining = maximumCharacters - TRUNCATION_MARKER.length();
        MutableComponent result = Component.empty();
        for (Component flat : component.toFlatList()) {
            if (remaining <= 0) break;
            String text = flat.getString();
            if (text.length() <= remaining) {
                result.append(flat);
                remaining -= text.length();
                continue;
            }
            int take = remaining;
            if (take > 0 && take < text.length() && Character.isHighSurrogate(text.charAt(take - 1))) {
                take--;
            }
            if (take > 0) {
                result.append(Component.literal(text.substring(0, take)).withStyle(flat.getStyle()));
            }
            remaining = 0;
        }
        return result.append(TRUNCATION_MARKER);
    }

    record Rendered(Component component, boolean truncated, List<Identifier> functionStack) {
        Rendered {
            functionStack = List.copyOf(functionStack);
        }
    }

    private record RenderValue(Component component, Number number) {
    }

    private record RenderItem(
            Map<String, RenderValue> fields,
            Component defaultMultiple,
            List<RenderItem> children
    ) {
        static RenderItem single(Component component) {
            RenderValue value = new RenderValue(component, null);
            return new RenderItem(Map.of("", value, "value", value), component, List.of());
        }

        RenderValue field(String name) throws DebugRuntimeException {
            RenderValue value = fields.get(name);
            if (value == null) throw new DebugRuntimeException("unknown_item_field", "unknown item field '" + name + "'");
            return value;
        }
    }

    private record DataGroup(
            Component label,
            int sourceIndex,
            int globalStart,
            List<RenderItem> children
    ) {
        RenderItem asRenderItem() {
            RenderValue value = new RenderValue(label, null);
            Map<String, RenderValue> fields = Map.of(
                    "", value,
                    "value", value,
                    "entity", value,
                    "entity_index", new RenderValue(numberValue(Integer.toString(sourceIndex)), sourceIndex),
                    "global_index", new RenderValue(numberValue(Integer.toString(globalStart)), globalStart)
            );
            return new RenderItem(fields, label, children);
        }
    }

    private static final class ItemCursor {
        private final List<RenderItem> items;
        private int index;

        private ItemCursor(List<RenderItem> items) {
            this.items = items;
        }

        boolean hasNext() {
            return index < items.size();
        }

        RenderItem next() {
            return items.get(index++);
        }

        int position() {
            return index;
        }
    }

    static final class RenderBudget {
        private int remainingLeaves;
        private int remainingSources;
        private long remainingNbtBytes;
        private boolean truncated;

        RenderBudget(int maximumLeaves) {
            this(maximumLeaves, Integer.MAX_VALUE, Long.MAX_VALUE);
        }

        RenderBudget(int maximumLeaves, int maximumSources, long maximumNbtBytes) {
            remainingLeaves = maximumLeaves;
            remainingSources = maximumSources;
            remainingNbtBytes = maximumNbtBytes;
        }

        boolean tryConsume() {
            if (remainingLeaves > 0) {
                remainingLeaves--;
                return true;
            }
            truncated = true;
            return false;
        }

        boolean tryVisitSource() {
            if (remainingSources > 0) {
                remainingSources--;
                return true;
            }
            truncated = true;
            return false;
        }

        boolean tryConsumeNbtBytes(long bytes) {
            if (bytes >= 0 && bytes <= remainingNbtBytes) {
                remainingNbtBytes -= bytes;
                return true;
            }
            truncated = true;
            return false;
        }

        boolean truncated() {
            return truncated;
        }
    }
}
