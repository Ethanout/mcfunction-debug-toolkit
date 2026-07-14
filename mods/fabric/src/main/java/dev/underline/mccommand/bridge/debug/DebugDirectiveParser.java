package dev.underline.mccommand.bridge.debug;

import dev.underline.mccommand.bridge.debug.DebugAst.CompositePart;
import dev.underline.mccommand.bridge.debug.DebugAst.ContextKey;
import dev.underline.mccommand.bridge.debug.DebugAst.ContextQuery;
import dev.underline.mccommand.bridge.debug.DebugAst.DataQuery;
import dev.underline.mccommand.bridge.debug.DebugAst.DataSource;
import dev.underline.mccommand.bridge.debug.DebugAst.ExpressionPart;
import dev.underline.mccommand.bridge.debug.DebugAst.FieldPart;
import dev.underline.mccommand.bridge.debug.DebugAst.Format;
import dev.underline.mccommand.bridge.debug.DebugAst.FormatPart;
import dev.underline.mccommand.bridge.debug.DebugAst.FormatText;
import dev.underline.mccommand.bridge.debug.DebugAst.NestedListPart;
import dev.underline.mccommand.bridge.debug.DebugAst.NumericFormat;
import dev.underline.mccommand.bridge.debug.DebugAst.Query;
import dev.underline.mccommand.bridge.debug.DebugAst.ScoreQuery;
import dev.underline.mccommand.bridge.debug.DebugAst.TemplatePart;
import dev.underline.mccommand.bridge.debug.DebugAst.TextPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DebugDirectiveParser {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_NUMERIC_SIZE = 32_000;
    private static final Pattern NUMERIC_FORMAT = Pattern.compile("([<>=^])?(0)?(\\d+)?(?:\\.(\\d+))?([dfegp])");

    public DebugAst.Directive parse(String functionId, int startLine, int endLine, String source)
            throws DebugParseException {
        if (source.length() > 32_000) {
            throw error("directive_too_long", 0, "directive exceeds 32000 characters");
        }
        List<TemplatePart> parts = parseTemplate(source, 0);
        if (parts.stream().noneMatch(ExpressionPart.class::isInstance)) {
            throw error("missing_expression", 0, "directive must contain at least one {...} expression");
        }
        return new DebugAst.Directive(functionId, startLine, endLine, source, parts);
    }

    private List<TemplatePart> parseTemplate(String source, int depth) throws DebugParseException {
        checkDepth(depth, 0);
        List<TemplatePart> parts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '\\') {
                index = appendEscape(source, index, text, 0);
                continue;
            }
            if (current == '}') {
                throw error("unexpected_closing_brace", index, "unexpected closing brace");
            }
            if (current != '{') {
                text.append(current);
                index++;
                continue;
            }
            flushText(parts, text);
            int close = matchingBrace(source, index, 0);
            String expression = source.substring(index + 1, close);
            parts.add(parseExpression(expression, index + 1, depth + 1));
            index = close + 1;
        }
        flushText(parts, text);
        return List.copyOf(parts);
    }

    private ExpressionPart parseExpression(String expression, int sourceOffset, int depth)
            throws DebugParseException {
        if (expression.isBlank()) {
            throw error("empty_query", sourceOffset, "empty query is only valid inside an item format");
        }

        List<Integer> colons = topLevelPositions(expression, ':', sourceOffset);
        for (int i = colons.size() - 1; i >= 0; i--) {
            int colon = colons.get(i);
            TrimmedSource left = trim(expression.substring(0, colon), sourceOffset);
            TrimmedSource right = trim(expression.substring(colon + 1), sourceOffset + colon + 1);
            if (!looksLikeFormat(right.source())) continue;
            Query query;
            try {
                query = parseQuery(left.source(), left.offset());
            } catch (DebugParseException ignoredCandidate) {
                // A namespace ID also contains ':'. Try an earlier candidate or the whole query.
                continue;
            }
            Format format = parseFormat(right.source(), right.offset(), depth + 1);
            return new ExpressionPart(query, format, sourceOffset);
        }

        TrimmedSource trimmed = trim(expression, sourceOffset);
        Query query = parseQuery(trimmed.source(), trimmed.offset());
        return new ExpressionPart(query, null, sourceOffset);
    }

    private Query parseQuery(String source, int offset) throws DebugParseException {
        List<Token> tokens = tokenize(source, offset);
        if (tokens.isEmpty()) throw error("empty_query", offset, "query cannot be empty");
        if (tokens.size() == 1) {
            String key = tokens.getFirst().text().toLowerCase(Locale.ROOT);
            return switch (key) {
                case "name" -> new ContextQuery(ContextKey.NAME);
                case "dim", "dimension" -> new ContextQuery(ContextKey.DIMENSION);
                case "pos", "position" -> new ContextQuery(ContextKey.POSITION);
                case "rot", "rotation" -> new ContextQuery(ContextKey.ROTATION);
                case "anch", "anchor" -> new ContextQuery(ContextKey.ANCHOR);
                case "fname", "function_name" -> new ContextQuery(ContextKey.FUNCTION_NAME);
                case "fstack", "function_stack" -> new ContextQuery(ContextKey.FUNCTION_STACK);
                case "self", "sname", "self_name", "feedback", "fdb" ->
                        throw error("removed_query", offset, "query '" + key + "' was removed");
                default -> throw error("unknown_query", offset, "unknown single-token query '" + key + "'");
            };
        }

        String first = tokens.getFirst().text().toLowerCase(Locale.ROOT);
        if (first.equals("storage")) {
            requireTokens(tokens, 3, offset, "storage requires an ID and NBT path");
            return new DataQuery(
                    DataSource.STORAGE,
                    tokens.get(1).text(),
                    List.of(),
                    remainder(source, tokens, 2),
                    offset + tokens.get(1).start(),
                    offset + tokens.get(2).start()
            );
        }
        if (first.equals("entity")) {
            requireTokens(tokens, 3, offset, "entity requires a selector and NBT path");
            return new DataQuery(
                    DataSource.ENTITY,
                    tokens.get(1).text(),
                    List.of(),
                    remainder(source, tokens, 2),
                    offset + tokens.get(1).start(),
                    offset + tokens.get(2).start()
            );
        }
        if (first.equals("block")) {
            requireTokens(tokens, 5, offset, "block requires x y z and an NBT path");
            return new DataQuery(
                    DataSource.BLOCK,
                    "",
                    List.of(tokens.get(1).text(), tokens.get(2).text(), tokens.get(3).text()),
                    remainder(source, tokens, 4),
                    offset + tokens.get(1).start(),
                    offset + tokens.get(4).start()
            );
        }
        if (tokens.size() == 2) {
            return new ScoreQuery(
                    tokens.get(0).text(),
                    tokens.get(1).text(),
                    offset + tokens.get(0).start(),
                    offset + tokens.get(1).start()
            );
        }
        throw error("invalid_query", offset, "query does not match a context, score, storage, entity, or block form");
    }

    private Format parseFormat(String source, int offset, int depth) throws DebugParseException {
        checkDepth(depth, offset);
        int leadingWhitespace = 0;
        while (leadingWhitespace < source.length() && Character.isWhitespace(source.charAt(leadingWhitespace))) {
            leadingWhitespace++;
        }
        String working = source.substring(leadingWhitespace).stripTrailing();
        int workingOffset = offset + leadingWhitespace;
        boolean strip = true;
        int modifierStart = trailingModifierStart(working);
        if (modifierStart >= 0) {
            String modifier = working.substring(modifierStart).trim();
            strip = modifier.equals("/strip");
            working = working.substring(0, modifierStart).stripTrailing();
        }

        List<Integer> ellipses = topLevelEllipses(working, workingOffset);
        if (ellipses.size() > 1) {
            throw error("multiple_ellipses", workingOffset + ellipses.get(1), "only one ... is allowed in a list-format scope");
        }
        boolean repeating = !ellipses.isEmpty();
        String pattern = working;
        if (repeating) {
            int ellipsis = ellipses.getFirst();
            if (!working.substring(ellipsis + 3).isBlank()) {
                throw error("non_terminal_ellipsis", workingOffset + ellipsis, "only /strip or /no_strip may follow terminal ...");
            }
            pattern = working.substring(0, ellipsis);
            if (pattern.isEmpty()) {
                throw error("empty_repeat", workingOffset + ellipsis, "... requires a preceding repeat pattern");
            }
        } else if (modifierStart >= 0) {
            throw error("modifier_without_ellipsis", workingOffset + modifierStart, "/strip and /no_strip require ...");
        }

        String trimmedPattern = pattern.trim();
        NumericFormat direct = isNumericFormat(trimmedPattern)
                ? parseNumericFormat(trimmedPattern, workingOffset + pattern.indexOf(trimmedPattern))
                : null;
        List<FormatPart> parts = direct == null ? parseFormatParts(pattern, workingOffset, depth + 1) : List.of();
        return new Format(source, pattern, parts, repeating, strip, direct);
    }

    private List<FormatPart> parseFormatParts(String source, int offset, int depth) throws DebugParseException {
        checkDepth(depth, offset);
        List<FormatPart> parts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '\\') {
                index = appendEscape(source, index, text, offset);
                continue;
            }
            if (current == '}') {
                throw error("unexpected_closing_brace", offset + index, "unexpected closing brace in format");
            }
            if (current != '{') {
                text.append(current);
                index++;
                continue;
            }
            flushFormatText(parts, text);
            int close = matchingBrace(source, index, offset);
            String content = source.substring(index + 1, close);
            parts.add(parseFormatBrace(content, offset + index + 1, depth + 1));
            index = close + 1;
        }
        flushFormatText(parts, text);
        return List.copyOf(parts);
    }

    private FormatPart parseFormatBrace(String content, int offset, int depth) throws DebugParseException {
        checkDepth(depth, offset);
        if (content.isEmpty()) return new FieldPart("", null, "{}", offset);
        if (isQuoted(content)) {
            String quotedBody = content.substring(1, content.length() - 1);
            return new CompositePart(
                    parseFormatParts(quotedBody, offset + 1, depth + 1),
                    "{" + content + "}",
                    offset
            );
        }
        if (!topLevelEllipses(content, offset).isEmpty()) {
            return new NestedListPart(parseFormat(content, offset, depth + 1), "{" + content + "}", offset);
        }

        List<Integer> colons = topLevelPositions(content, ':', offset);
        String field = content.trim();
        NumericFormat numeric = null;
        if (!colons.isEmpty()) {
            int colon = colons.getLast();
            field = content.substring(0, colon).trim();
            String numericSource = content.substring(colon + 1).trim();
            if (!isNumericFormat(numericSource)) {
                throw error("invalid_numeric_format", offset + colon + 1, "unsupported numeric format");
            }
            numeric = parseNumericFormat(numericSource, offset + colon + 1);
        }
        if (!field.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw error("invalid_item_field", offset, "invalid item field '" + field + "'");
        }
        return new FieldPart(field, numeric, "{" + content + "}", offset);
    }

    private static boolean isNumericFormat(String source) {
        return NUMERIC_FORMAT.matcher(source).matches();
    }

    private static NumericFormat parseNumericFormat(String source, int offset) throws DebugParseException {
        Matcher matcher = NUMERIC_FORMAT.matcher(source);
        if (!matcher.matches()) return null;
        Character align = matcher.group(1) == null ? null : matcher.group(1).charAt(0);
        boolean zero = matcher.group(2) != null;
        int width = parseNumericSize(
                matcher.group(3),
                offset + (matcher.start(3) < 0 ? 0 : matcher.start(3)),
                "width"
        );
        int precision = parseNumericSize(
                matcher.group(4),
                offset + (matcher.start(4) < 0 ? 0 : matcher.start(4)),
                "precision"
        );
        return new NumericFormat(align, zero, width, precision, matcher.group(5).charAt(0));
    }

    private static int parseNumericSize(String value, int offset, String name) throws DebugParseException {
        if (value == null) return -1;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > MAX_NUMERIC_SIZE) {
                throw error("numeric_format_too_large", offset,
                        "numeric " + name + " exceeds " + MAX_NUMERIC_SIZE);
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw error("numeric_format_too_large", offset,
                    "numeric " + name + " exceeds " + MAX_NUMERIC_SIZE);
        }
    }

    private static boolean looksLikeFormat(String value) {
        if (value.isEmpty()) return false;
        return value.indexOf('{') >= 0 || value.contains("...") || isNumericFormat(value);
    }

    private static int trailingModifierStart(String value) {
        String stripped = value.stripTrailing();
        if (stripped.endsWith("/no_strip")) return stripped.length() - "/no_strip".length();
        if (stripped.endsWith("/strip")) return stripped.length() - "/strip".length();
        return -1;
    }

    private static List<Integer> topLevelEllipses(String source, int baseOffset) throws DebugParseException {
        List<Integer> result = new ArrayList<>();
        ScanState state = new ScanState();
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            if (state.consume(i, current, baseOffset)) continue;
            if (state.topLevel() && current == '.' && i + 2 < source.length()
                    && source.charAt(i + 1) == '.' && source.charAt(i + 2) == '.') {
                result.add(i);
                i += 2;
            }
        }
        state.finish(baseOffset + source.length());
        return result;
    }

    private static List<Integer> topLevelPositions(String source, char wanted, int baseOffset) throws DebugParseException {
        List<Integer> result = new ArrayList<>();
        ScanState state = new ScanState();
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            if (state.consume(i, current, baseOffset)) continue;
            if (state.topLevel() && current == wanted) result.add(i);
        }
        state.finish(baseOffset + source.length());
        return result;
    }

    private static List<Token> tokenize(String source, int offset) throws DebugParseException {
        List<Token> tokens = new ArrayList<>();
        ScanState state = new ScanState();
        int tokenStart = -1;
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            boolean topBefore = state.topLevel();
            if (topBefore && Character.isWhitespace(current)) {
                if (tokenStart >= 0) {
                    tokens.add(new Token(source.substring(tokenStart, i), tokenStart, i));
                    tokenStart = -1;
                }
                continue;
            }
            if (tokenStart < 0) tokenStart = i;
            state.consume(i, current, offset);
        }
        state.finish(offset + source.length());
        if (tokenStart >= 0) tokens.add(new Token(source.substring(tokenStart), tokenStart, source.length()));
        return tokens;
    }

    private static int matchingBrace(String source, int open, int baseOffset) throws DebugParseException {
        int depth = 0;
        boolean escaped = false;
        char quote = 0;
        int quoteStart = -1;
        for (int i = open; i < source.length(); i++) {
            char current = source.charAt(i);
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
                quoteStart = i;
                continue;
            }
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) return i;
        }
        if (quote != 0) throw error("unclosed_quote", baseOffset + quoteStart, "unclosed quote");
        throw error("unclosed_brace", baseOffset + open, "unclosed opening brace");
    }

    private static int appendEscape(String source, int slash, StringBuilder output, int baseOffset) throws DebugParseException {
        if (slash + 1 >= source.length()) throw error("dangling_escape", baseOffset + slash, "dangling escape at end of directive");
        char escaped = source.charAt(slash + 1);
        output.append(switch (escaped) {
            case '{' -> '{';
            case '}' -> '}';
            case '\\' -> '\\';
            case '"' -> '"';
            case 'n' -> '\n';
            case 't' -> '\t';
            default -> throw error("unknown_escape", baseOffset + slash, "unknown escape \\" + escaped + "'");
        });
        return slash + 2;
    }

    private static boolean isQuoted(String content) {
        if (content.length() < 2) return false;
        char quote = content.charAt(0);
        return (quote == '"' || quote == '\'') && content.charAt(content.length() - 1) == quote;
    }

    private static String remainder(String source, List<Token> tokens, int tokenIndex) {
        return source.substring(tokens.get(tokenIndex).start()).trim();
    }

    private static void requireTokens(List<Token> tokens, int count, int offset, String message)
            throws DebugParseException {
        if (tokens.size() < count) throw error("missing_query_argument", offset, message);
    }

    private static void flushText(List<TemplatePart> parts, StringBuilder text) {
        if (!text.isEmpty()) {
            parts.add(new TextPart(text.toString()));
            text.setLength(0);
        }
    }

    private static void flushFormatText(List<FormatPart> parts, StringBuilder text) {
        if (!text.isEmpty()) {
            parts.add(new FormatText(text.toString()));
            text.setLength(0);
        }
    }

    private static TrimmedSource trim(String source, int offset) {
        int start = 0;
        int end = source.length();
        while (start < end && Character.isWhitespace(source.charAt(start))) start++;
        while (end > start && Character.isWhitespace(source.charAt(end - 1))) end--;
        return new TrimmedSource(source.substring(start, end), offset + start);
    }

    private static void checkDepth(int depth, int offset) throws DebugParseException {
        if (depth > MAX_DEPTH) {
            throw error("format_too_deep", offset, "template nesting exceeds " + MAX_DEPTH);
        }
    }

    private static DebugParseException error(String code, int offset, String message) {
        return new DebugParseException(code, Math.max(0, offset), message);
    }

    private record Token(String text, int start, int end) {
    }

    private record TrimmedSource(String source, int offset) {
    }

    private static final class ScanState {
        private int square;
        private int curly;
        private int round;
        private char quote;
        private int quoteOffset = -1;
        private boolean escaped;

        boolean consume(int index, char current, int baseOffset) throws DebugParseException {
            if (escaped) {
                escaped = false;
                return true;
            }
            if (current == '\\') {
                escaped = true;
                return true;
            }
            if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                    quoteOffset = -1;
                }
                return true;
            }
            if (current == '"' || current == '\'') {
                quote = current;
                quoteOffset = baseOffset + index;
                return true;
            }
            switch (current) {
                case '[' -> square++;
                case ']' -> square--;
                case '{' -> curly++;
                case '}' -> curly--;
                case '(' -> round++;
                case ')' -> round--;
                default -> {
                    return false;
                }
            }
            if (square < 0 || curly < 0 || round < 0) {
                throw error("unbalanced_group", baseOffset + index, "unexpected closing delimiter");
            }
            return true;
        }

        boolean topLevel() {
            return quote == 0 && square == 0 && curly == 0 && round == 0;
        }

        void finish(int offset) throws DebugParseException {
            if (escaped) throw error("dangling_escape", offset - 1, "dangling escape");
            if (quote != 0) throw error("unclosed_quote", quoteOffset, "unclosed quote");
            if (square != 0 || curly != 0 || round != 0) {
                throw error("unbalanced_group", offset, "unclosed selector, NBT, or format group");
            }
        }
    }
}
