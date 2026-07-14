package dev.underline.mccommand.bridge.debug;

import com.mojang.brigadier.StringReader;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DebugDirectiveParserTest {
    private final DebugDirectiveParser parser = new DebugDirectiveParser();

    @BeforeEach
    void resetRegistry() {
        DebugDirectiveRegistry.clearForReload();
    }

    @Test
    void parsesContextAndEscapedLiteralBraces() throws Exception {
        DebugAst.Directive directive = parser.parse("test:main", 4, 4, "name=\\{{name}\\}");
        assertEquals(3, directive.parts().size());
        assertEquals("name={", assertInstanceOf(DebugAst.TextPart.class, directive.parts().get(0)).text());
        DebugAst.ExpressionPart expression = assertInstanceOf(DebugAst.ExpressionPart.class, directive.parts().get(1));
        assertEquals(DebugAst.ContextKey.NAME, assertInstanceOf(DebugAst.ContextQuery.class, expression.query()).key());
        assertEquals("}", assertInstanceOf(DebugAst.TextPart.class, directive.parts().get(2)).text());
    }

    @Test
    void keepsNamespaceColonInsideStorageQuery() throws Exception {
        DebugAst.Directive directive = parser.parse("test:main", 1, 1, "{storage example:data path.to.value}");
        DebugAst.ExpressionPart expression = assertInstanceOf(DebugAst.ExpressionPart.class, directive.parts().getFirst());
        DebugAst.DataQuery query = assertInstanceOf(DebugAst.DataQuery.class, expression.query());
        assertEquals("example:data", query.target());
        assertEquals("path.to.value", query.path());
        assertEquals(null, expression.format());
    }

    @Test
    void parsesSelectorNbtAndCompositeRepeat() throws Exception {
        String source = "{entity @e[nbt={Tags:['a b']}] data.values[]: {\"[{entity}] {index}:{value}\"}, ... /no_strip}";
        DebugAst.Directive directive = parser.parse("test:main", 1, 1, source);
        DebugAst.ExpressionPart expression = assertInstanceOf(DebugAst.ExpressionPart.class, directive.parts().getFirst());
        DebugAst.DataQuery query = assertInstanceOf(DebugAst.DataQuery.class, expression.query());
        assertEquals("@e[nbt={Tags:['a b']}]", query.target());
        assertTrue(expression.format().repeating());
        assertEquals(false, expression.format().strip());
        assertInstanceOf(DebugAst.CompositePart.class, expression.format().parts().getFirst());
    }

    @Test
    void rejectsNonTerminalEllipsis() {
        DebugParseException error = assertThrows(DebugParseException.class,
                () -> parser.parse("test:main", 1, 1, "{@e num: {}, ... trailing}"));
        assertEquals("non_terminal_ellipsis", error.code());
    }

    @Test
    void parsesNestedIndependentRepeatScopes() throws Exception {
        DebugAst.Directive directive = parser.parse(
                "test:main", 1, 1, "{entity @e data.values[]: {{}, ...}\\n ...}");
        DebugAst.Format outer = assertInstanceOf(DebugAst.ExpressionPart.class, directive.parts().getFirst()).format();
        assertTrue(outer.repeating());
        assertInstanceOf(DebugAst.NestedListPart.class, outer.parts().getFirst());
    }

    @Test
    void ignoresUnbalancedBracesInsideQuotedQueryText() throws Exception {
        DebugAst.Directive directive = parser.parse(
                "test:main", 1, 1, "{entity @e[tag='open{'] Pos[]}");
        DebugAst.ExpressionPart expression = assertInstanceOf(
                DebugAst.ExpressionPart.class, directive.parts().getFirst());
        assertEquals("@e[tag='open{']", assertInstanceOf(DebugAst.DataQuery.class, expression.query()).target());
    }

    @Test
    void rejectsOversizedNumericWidthAndPrecision() {
        DebugParseException width = assertThrows(DebugParseException.class,
                () -> parser.parse("test:main", 1, 1, "{@s num: 32001d}"));
        assertEquals("numeric_format_too_large", width.code());

        DebugParseException precision = assertThrows(DebugParseException.class,
                () -> parser.parse("test:main", 1, 1, "{@s num: .32001f}"));
        assertEquals("numeric_format_too_large", precision.code());
    }

    @Test
    void preprocessorPreservesLineCountAndDoesNotConsumeNormalLine() {
        List<String> result = DebugFunctionPreprocessor.preprocess(
                Identifier.parse("test:main"),
                List.of("say before", "#! values: {", "#! @e num: {}, ...", "#! }", "say after")
        );
        assertEquals(5, result.size());
        assertTrue(result.get(1).startsWith(DebugFunctionPreprocessor.INTERNAL_COMMAND));
        assertEquals("#", result.get(2));
        assertEquals("#", result.get(3));
        assertEquals("say after", result.get(4));
        assertEquals(1, DebugDirectiveRegistry.directiveCount());
    }

    @Test
    void malformedMultilineDirectiveIsDroppedButNextCommandSurvives() {
        List<String> result = DebugFunctionPreprocessor.preprocess(
                Identifier.parse("test:broken"),
                List.of("#! broken: {name", "say still_here")
        );
        assertEquals(List.of("#", "say still_here"), result);
        assertEquals(0, DebugDirectiveRegistry.directiveCount());
        assertEquals("unclosed_brace", DebugDirectiveRegistry.diagnosticsSince(0).getFirst().code());
    }

    @Test
    void mapsThirdLineParserErrorToPhysicalLineAndColumn() {
        List<String> result = DebugFunctionPreprocessor.preprocess(
                Identifier.parse("test:broken"),
                List.of(
                        "say before",
                        "  #! {",
                        "\t#! entity @e Pos[]:",
                        "    #! {bad-field}}",
                        "say after"
                )
        );
        assertEquals("say after", result.get(4));
        DebugDiagnostic diagnostic = DebugDirectiveRegistry.diagnosticsSince(0).getFirst();
        assertEquals("invalid_item_field", diagnostic.code());
        assertEquals(4, diagnostic.line());
        assertEquals(9, diagnostic.column());
    }

    @Test
    void validatesNativeSyntaxDuringReloadWithMappedLocation() {
        List<String> result = DebugFunctionPreprocessor.preprocess(
                Identifier.parse("test:native"),
                List.of(
                        "  #! {",
                        "    #! entity @e[limit=oops] Pos[]}",
                        "say after"
                )
        );
        assertEquals(List.of("#", "#", "say after"), result);
        assertEquals(0, DebugDirectiveRegistry.directiveCount());
        DebugDiagnostic diagnostic = DebugDirectiveRegistry.diagnosticsSince(0).getFirst();
        assertEquals("invalid_native_syntax", diagnostic.code());
        assertEquals(2, diagnostic.line());
        assertTrue(diagnostic.column() >= 17);
    }

    @Test
    void rejectsNativePathCoordinateAndScoreHolderSyntaxDuringReload() {
        List<String> result = DebugFunctionPreprocessor.preprocess(
                Identifier.parse("test:native_forms"),
                List.of(
                        "#! {storage demo:test foo..bar}",
                        "#! {block ~ nope ~ foo}",
                        "#! {@e[limit=oops] num}",
                        "say after"
                )
        );
        assertEquals(List.of("#", "#", "#", "say after"), result);
        assertEquals(0, DebugDirectiveRegistry.directiveCount());
        assertEquals(3, DebugDirectiveRegistry.diagnosticsSince(0).size());
    }

    @Test
    void rejectsFieldsAndNestedListsThatDoNotMatchTheQuery() {
        List<String> result = DebugFunctionPreprocessor.preprocess(
                Identifier.parse("test:fields"),
                List.of(
                        "#! {@s num: {\"{value}\"}}",
                        "#! {name: {\"{score}\"}}",
                        "#! {@s num: {{}, ...}}",
                        "say after"
                )
        );
        assertEquals(List.of("#", "#", "#", "say after"), result);
        assertEquals(0, DebugDirectiveRegistry.directiveCount());
        assertEquals(List.of("unknown_item_field", "unknown_item_field", "nested_list_requires_data"),
                DebugDirectiveRegistry.diagnosticsSince(0).stream().map(DebugDiagnostic::code).toList());
    }

    @Test
    void vanillaMissingNbtPathProducesNoValues() throws Exception {
        NbtPathArgument.NbtPath path = NbtPathArgument.nbtPath().parse(new StringReader("missing.value"));
        assertTrue(DebugDirectiveRenderer.valuesAt(path, new CompoundTag()).isEmpty());
    }

    @Test
    void zeroPaddingFollowsTheSignAndPlainSignificantFormatHandlesNonFiniteValues() {
        DebugAst.NumericFormat paddedInteger = new DebugAst.NumericFormat(null, true, 4, -1, 'd');
        assertEquals("-003", DebugDirectiveRenderer.formatNumber(-3, paddedInteger));

        DebugAst.NumericFormat plainSignificant = new DebugAst.NumericFormat(null, false, -1, 6, 'p');
        assertEquals("NaN", DebugDirectiveRenderer.formatNumber(Double.NaN, plainSignificant));
        assertEquals("Infinity", DebugDirectiveRenderer.formatNumber(Double.POSITIVE_INFINITY, plainSignificant));
        DebugAst.NumericFormat exactLong = new DebugAst.NumericFormat(null, false, -1, 16, 'p');
        assertEquals("9007199254740993", DebugDirectiveRenderer.formatNumber(9_007_199_254_740_993L, exactLong));
        assertNull(DebugDirectiveRenderer.formatNumber(1.9d, paddedInteger));
        assertNull(DebugDirectiveRenderer.formatNumber(Double.NaN, paddedInteger));
        assertNull(DebugDirectiveRenderer.formatNumber(Double.POSITIVE_INFINITY, paddedInteger));
    }

    @Test
    void characterTruncationPreservesTheFinalFragmentStyleAndHover() {
        Component source = Component.literal("abcdefghijklmnopqrstuvwxyz")
                .withStyle(style -> style.withHoverEvent(
                        new HoverEvent.ShowText(Component.literal("holder"))));
        Component truncated = DebugDirectiveRenderer.truncateWithMarker(source, 20);
        assertEquals("abcdefgh…[truncated]", truncated.getString());
        assertEquals(20, truncated.getString().length());
        assertTrue(truncated.toFlatList().getFirst().getStyle().getHoverEvent() != null);
    }

    @Test
    void renderBudgetMarksOnlyActualOverflow() {
        DebugDirectiveRenderer.RenderBudget budget = new DebugDirectiveRenderer.RenderBudget(2);
        assertTrue(budget.tryConsume());
        assertTrue(budget.tryConsume());
        assertEquals(false, budget.truncated());
        assertEquals(false, budget.tryConsume());
        assertTrue(budget.truncated());
    }

    @Test
    void renderBudgetBoundsSourceVisitsAndNbtBeforeAValueIsRendered() {
        DebugDirectiveRenderer.RenderBudget sources =
                new DebugDirectiveRenderer.RenderBudget(256, 2, 1_024);
        assertTrue(sources.tryVisitSource());
        assertTrue(sources.tryVisitSource());
        assertEquals(false, sources.tryVisitSource());
        assertTrue(sources.truncated());

        DebugDirectiveRenderer.RenderBudget nbt =
                new DebugDirectiveRenderer.RenderBudget(256, 2, 10);
        assertTrue(nbt.tryConsumeNbtBytes(6));
        assertEquals(false, nbt.tryConsumeNbtBytes(5));
        assertTrue(nbt.truncated());
    }

    @Test
    void entitySelectorsAreCappedWithoutCorruptingNestedSelectorData() {
        assertEquals("@e[limit=512]", DebugDirectiveRenderer.capEntitySelector("@e", 512));
        assertEquals("@e[tag=test,limit=512]",
                DebugDirectiveRenderer.capEntitySelector("@e[tag=test]", 512));
        assertEquals("@e[nbt={Tags:[\"a\",\"b\"]},limit=512]",
                DebugDirectiveRenderer.capEntitySelector("@e[nbt={Tags:[\"a\",\"b\"]}]", 512));
        assertEquals("@e[tag=test,limit=512,sort=nearest]",
                DebugDirectiveRenderer.capEntitySelector("@e[tag=test,limit=999,sort=nearest]", 512));
    }

    @Test
    void debugEventCursorReportsDroppedHistory() throws Exception {
        DebugEventStore.clearForTests();
        DebugAst.Directive directive = parser.parse("test:events", 1, 1, "{name}");
        for (int index = 0; index < 2_050; index++) {
            DebugEventStore.add(directive, "message-" + index, null, List.of(), false, null, null);
        }
        DebugEventStore.Snapshot snapshot = DebugEventStore.snapshotSince(0);
        assertEquals(2_050, snapshot.nextId());
        assertEquals(3, snapshot.oldestId());
        assertEquals(2_048, snapshot.events().size());
        assertTrue(snapshot.dropped());
    }

    @Test
    void debugEventPagesAdvanceOnlyThroughReturnedItemsAndOmitHugeComponents() throws Exception {
        DebugEventStore.clearForTests();
        DebugAst.Directive directive = parser.parse("test:pages", 1, 1, "{name}");
        for (int index = 0; index < 5; index++) {
            DebugEventStore.add(directive, "message-" + index, null, List.of(), false, null, null);
        }
        DebugEventStore.Snapshot first = DebugEventStore.snapshotSince(0, 2);
        assertEquals(2, first.events().size());
        assertEquals(2, first.nextId());
        assertEquals(5, first.latestId());
        assertTrue(first.more());

        DebugEvent omitted = DebugEventStore.add(
                directive, "bounded", "x".repeat(70_000), List.of(), false, null, null);
        assertTrue(omitted.componentOmitted());
        assertNull(omitted.componentJson());
    }

    @Test
    void diagnosticCursorReportsDroppedHistory() {
        long start = DebugDirectiveRegistry.diagnosticsSnapshotSince(0).nextId();
        for (int index = 0; index < 1_026; index++) {
            DebugDirectiveRegistry.addDiagnostic(
                    "reload", "test", "test:diagnostics", 1, 1, "message", "source");
        }
        DebugDirectiveRegistry.DiagnosticSnapshot snapshot =
                DebugDirectiveRegistry.diagnosticsSnapshotSince(start);
        assertEquals(start + 1_026, snapshot.nextId());
        assertEquals(start + 3, snapshot.oldestId());
        assertEquals(1_024, snapshot.diagnostics().size());
        assertTrue(snapshot.dropped());
    }

    @Test
    void failedReloadKeepsPreviousRegistryGeneration() throws Exception {
        String oldId = DebugDirectiveRegistry.register(parser.parse("test:old", 1, 1, "{name}"));
        long reload = DebugDirectiveRegistry.beginReload();
        String newId = DebugDirectiveRegistry.register(parser.parse("test:new", 1, 1, "{name}"));
        assertTrue(DebugDirectiveRegistry.get(oldId) != null);
        assertEquals(null, DebugDirectiveRegistry.get(newId));
        DebugDirectiveRegistry.finishReload(reload, false);
        assertEquals(1, DebugDirectiveRegistry.directiveCount());
        assertTrue(DebugDirectiveRegistry.get(oldId) != null);
        assertEquals(null, DebugDirectiveRegistry.get(newId));
    }

    @Test
    void successfulReloadAtomicallyPrunesOldGeneration() throws Exception {
        String oldId = DebugDirectiveRegistry.register(parser.parse("test:old", 1, 1, "{name}"));
        long reload = DebugDirectiveRegistry.beginReload();
        String newId = DebugDirectiveRegistry.register(parser.parse("test:new", 1, 1, "{name}"));
        assertTrue(DebugDirectiveRegistry.get(oldId) != null);
        assertEquals(null, DebugDirectiveRegistry.get(newId));
        DebugDirectiveRegistry.finishReload(reload, true);
        assertEquals(1, DebugDirectiveRegistry.directiveCount());
        assertEquals(null, DebugDirectiveRegistry.get(oldId));
        assertTrue(DebugDirectiveRegistry.get(newId) != null);
    }
}
