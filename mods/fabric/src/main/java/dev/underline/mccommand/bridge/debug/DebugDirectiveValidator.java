package dev.underline.mccommand.bridge.debug;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.underline.mccommand.bridge.debug.DebugAst.CompositePart;
import dev.underline.mccommand.bridge.debug.DebugAst.ContextQuery;
import dev.underline.mccommand.bridge.debug.DebugAst.DataQuery;
import dev.underline.mccommand.bridge.debug.DebugAst.ExpressionPart;
import dev.underline.mccommand.bridge.debug.DebugAst.FieldPart;
import dev.underline.mccommand.bridge.debug.DebugAst.Format;
import dev.underline.mccommand.bridge.debug.DebugAst.FormatPart;
import dev.underline.mccommand.bridge.debug.DebugAst.NestedListPart;
import dev.underline.mccommand.bridge.debug.DebugAst.Query;
import dev.underline.mccommand.bridge.debug.DebugAst.ScoreQuery;
import dev.underline.mccommand.bridge.debug.DebugAst.SelectorQuery;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.commands.arguments.ScoreHolderArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.resources.Identifier;

import java.util.Set;

public final class DebugDirectiveValidator {
    private static final Set<String> CONTEXT_FIELDS = Set.of("", "value");
    private static final Set<String> SCORE_FIELDS = Set.of("", "score", "name", "display_name", "holder");
    private static final Set<String> DATA_LEAF_FIELDS = Set.of(
            "", "value", "entity", "index", "entity_index", "global_index");
    private static final Set<String> DATA_GROUP_FIELDS = Set.of(
            "", "value", "entity", "entity_index", "global_index");

    private DebugDirectiveValidator() {
    }

    public static void validate(DebugAst.Directive directive) throws DebugParseException {
        for (DebugAst.TemplatePart part : directive.parts()) {
            if (!(part instanceof ExpressionPart expression)) continue;
            validateQuery(expression);
            if (expression.format() != null) validateFormat(expression.query(), expression.format(), expression.offset());
        }
    }

    private static void validateQuery(ExpressionPart expression) throws DebugParseException {
        Query query = expression.query();
        if (query instanceof SelectorQuery selector) {
            ensureConsumed(EntityArgument.entities(), selector.selector(), selector.selectorOffset(), "entity selector");
        } else if (query instanceof ScoreQuery score) {
            ensureConsumed(
                    ScoreHolderArgument.scoreHolders(),
                    score.holder(),
                    score.holderOffset(),
                    "score holder"
            );
        } else if (query instanceof DataQuery data) {
            switch (data.source()) {
                case STORAGE -> {
                    if (Identifier.tryParse(data.target()) == null) {
                        throw syntax("invalid_storage_id", data.targetOffset(),
                                "invalid storage ID '" + data.target() + "'");
                    }
                }
                case ENTITY -> ensureConsumed(
                        EntityArgument.entities(), data.target(), data.targetOffset(), "entity selector");
                case BLOCK -> ensureConsumed(
                        BlockPosArgument.blockPos(),
                        String.join(" ", data.coordinates()),
                        data.targetOffset(),
                        "block position"
                );
            }
            ensureConsumed(NbtPathArgument.nbtPath(), data.path(), data.pathOffset(), "NBT path");
        }
    }

    private static <T> void ensureConsumed(
            com.mojang.brigadier.arguments.ArgumentType<T> parser,
            String source,
            int baseOffset,
            String description
    ) throws DebugParseException {
        StringReader reader = new StringReader(source);
        try {
            parser.parse(reader);
        } catch (CommandSyntaxException error) {
            int cursor = error.getCursor() < 0 ? 0 : error.getCursor();
            throw syntax("invalid_native_syntax", baseOffset + cursor,
                    "invalid " + description + ": " + error.getMessage());
        }
        if (reader.canRead()) {
            throw syntax("trailing_native_syntax", baseOffset + reader.getCursor(),
                    "trailing input in " + description + " at " + reader.getCursor());
        }
    }

    private static void validateFormat(Query query, Format format, int offset) throws DebugParseException {
        boolean hierarchicalData = query instanceof DataQuery && containsNestedList(format.parts());
        Set<String> fields = fieldsFor(query, hierarchicalData);
        validateParts(query, format.parts(), fields, offset);
    }

    private static void validateParts(
            Query query,
            java.util.List<FormatPart> parts,
            Set<String> fields,
            int fallbackOffset
    ) throws DebugParseException {
        for (FormatPart part : parts) {
            if (part instanceof FieldPart field && !fields.contains(field.field())) {
                throw syntax("unknown_item_field", field.offset(),
                        "field '" + field.field() + "' is not available for this query");
            }
            if (part instanceof CompositePart composite) {
                validateParts(query, composite.parts(), fields, composite.offset());
            }
            if (part instanceof NestedListPart nested) {
                if (!(query instanceof DataQuery)) {
                    throw syntax("nested_list_requires_data", nested.offset(),
                            "nested list formats are only valid for entity, storage, or block data");
                }
                validateParts(query, nested.format().parts(), DATA_LEAF_FIELDS, nested.offset());
            }
        }
    }

    private static Set<String> fieldsFor(Query query, boolean hierarchicalData) {
        if (query instanceof ContextQuery) return CONTEXT_FIELDS;
        if (query instanceof SelectorQuery) return CONTEXT_FIELDS;
        if (query instanceof ScoreQuery) return SCORE_FIELDS;
        return hierarchicalData ? DATA_GROUP_FIELDS : DATA_LEAF_FIELDS;
    }

    private static boolean containsNestedList(java.util.List<FormatPart> parts) {
        for (FormatPart part : parts) {
            if (part instanceof NestedListPart) return true;
            if (part instanceof CompositePart composite && containsNestedList(composite.parts())) return true;
        }
        return false;
    }

    private static DebugParseException syntax(String code, int offset, String message) {
        return new DebugParseException(code, Math.max(0, offset), message);
    }
}
