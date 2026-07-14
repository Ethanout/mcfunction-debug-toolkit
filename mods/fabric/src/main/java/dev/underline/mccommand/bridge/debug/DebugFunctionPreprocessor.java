package dev.underline.mccommand.bridge.debug;

import dev.underline.mccommand.bridge.McCommandBridge;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public final class DebugFunctionPreprocessor {
    public static final String INTERNAL_COMMAND = "mcdebug_internal emit ";
    private static final DebugDirectiveParser PARSER = new DebugDirectiveParser();

    private DebugFunctionPreprocessor() {
    }

    public static List<String> preprocess(Identifier functionId, List<String> input) {
        List<String> output = new ArrayList<>(input);
        for (int index = 0; index < input.size();) {
            String line = input.get(index);
            if (!isDirectiveLine(line)) {
                index++;
                continue;
            }

            int start = index;
            StringBuilder source = new StringBuilder();
            List<SourceLine> sourceLines = new ArrayList<>();
            appendDirectiveLine(source, sourceLines, line, index);
            int balance = braceBalance(source);
            index++;
            while (balance > 0 && index < input.size() && isDirectiveLine(input.get(index))) {
                appendDirectiveLine(source, sourceLines, input.get(index), index);
                balance = braceBalance(source);
                index++;
            }

            int end = index - 1;
            for (int consumed = start; consumed <= end; consumed++) output.set(consumed, "#");
            try {
                DebugAst.Directive directive = PARSER.parse(
                        functionId.toString(), start + 1, end + 1, source.toString());
                DebugDirectiveValidator.validate(directive);
                String id = DebugDirectiveRegistry.register(directive);
                output.set(start, INTERNAL_COMMAND + id);
            } catch (DebugParseException error) {
                SourcePosition position = sourcePosition(sourceLines, source.length(), error.offset());
                warn(functionId.toString(), position.line(), error.code(), position.column(),
                        error.getMessage(), source.toString());
            }
        }
        return List.copyOf(output);
    }

    private static boolean isDirectiveLine(String line) {
        return line.stripLeading().startsWith("#!");
    }

    private static void appendDirectiveLine(
            StringBuilder source,
            List<SourceLine> sourceLines,
            String line,
            int lineIndex
    ) {
        if (!source.isEmpty()) source.append('\n');
        int contentStart = directiveContentStart(line);
        int mergedStart = source.length();
        String content = line.substring(contentStart);
        source.append(content);
        sourceLines.add(new SourceLine(mergedStart, content.length(), lineIndex + 1, contentStart));
    }

    private static int directiveContentStart(String line) {
        int index = 0;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) index++;
        index += 2;
        if (index < line.length() && line.charAt(index) == ' ') index++;
        return index;
    }

    private static SourcePosition sourcePosition(List<SourceLine> lines, int sourceLength, int offset) {
        int bounded = Math.max(0, Math.min(offset, sourceLength));
        SourceLine selected = lines.getFirst();
        for (SourceLine line : lines) {
            if (line.mergedStart() > bounded) break;
            selected = line;
        }
        int localOffset = Math.max(0, Math.min(bounded - selected.mergedStart(), selected.length()));
        return new SourcePosition(selected.physicalLine(), selected.contentStart() + localOffset + 1);
    }

    private static int braceBalance(CharSequence value) {
        int balance = 0;
        boolean escaped = false;
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
            } else if (quote != 0) {
                if (current == quote) quote = 0;
            } else if (balance > 0 && (current == '"' || current == '\'')) {
                quote = current;
            } else if (current == '{') {
                balance++;
            } else if (current == '}') {
                balance--;
            }
        }
        return balance;
    }

    private record SourceLine(int mergedStart, int length, int physicalLine, int contentStart) {
    }

    private record SourcePosition(int line, int column) {
    }

    private static void warn(
            String functionId,
            int line,
            String code,
            int column,
            String message,
            String source
    ) {
        DebugDirectiveRegistry.addDiagnostic("reload", code, functionId, line, column, message, source);
        McCommandBridge.LOGGER.warn("Invalid #! directive at {}:{}:{} [{}]: {}", functionId, line, column, code, message);
    }
}
