package dev.underline.mccommand.bridge.debug;

import java.util.List;

public final class DebugAst {
    private DebugAst() {
    }

    public record Directive(
            String functionId,
            int startLine,
            int endLine,
            String source,
            List<TemplatePart> parts
    ) {
        public Directive {
            parts = List.copyOf(parts);
        }
    }

    public sealed interface TemplatePart permits TextPart, ExpressionPart {
    }

    public record TextPart(String text) implements TemplatePart {
    }

    public record ExpressionPart(Query query, Format format, int offset) implements TemplatePart {
    }

    public sealed interface Query permits ContextQuery, ScoreQuery, DataQuery {
    }

    public enum ContextKey {
        NAME,
        DIMENSION,
        POSITION,
        ROTATION,
        ANCHOR,
        FUNCTION_NAME,
        FUNCTION_STACK
    }

    public record ContextQuery(ContextKey key) implements Query {
    }

    public record ScoreQuery(
            String holder,
            String objective,
            int holderOffset,
            int objectiveOffset
    ) implements Query {
    }

    public enum DataSource {
        STORAGE,
        ENTITY,
        BLOCK
    }

    public record DataQuery(
            DataSource source,
            String target,
            List<String> coordinates,
            String path,
            int targetOffset,
            int pathOffset
    ) implements Query {
        public DataQuery {
            coordinates = List.copyOf(coordinates);
        }
    }

    public record Format(
            String source,
            String pattern,
            List<FormatPart> parts,
            boolean repeating,
            boolean strip,
            NumericFormat directNumericFormat
    ) {
        public Format {
            parts = List.copyOf(parts);
        }
    }

    public sealed interface FormatPart permits FormatText, FieldPart, CompositePart, NestedListPart {
    }

    public record FormatText(String text) implements FormatPart {
    }

    /** An empty field name is the current value, as in {}. */
    public record FieldPart(
            String field,
            NumericFormat numericFormat,
            String raw,
            int offset
    ) implements FormatPart {
    }

    public record CompositePart(List<FormatPart> parts, String raw, int offset) implements FormatPart {
        public CompositePart {
            parts = List.copyOf(parts);
        }
    }

    public record NestedListPart(Format format, String raw, int offset) implements FormatPart {
    }

    public record NumericFormat(
            Character align,
            boolean zeroPad,
            int width,
            int precision,
            char type
    ) {
    }
}
