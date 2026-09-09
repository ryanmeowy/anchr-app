package com.anchr.core.conversation.application.impl;

import com.anchr.core.conversation.application.model.EvidenceCheckReport.Range;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Lossless structural partitioning and deterministic within-field coalescing. */
final class EvidenceInspectionUnits {
    private static final Set<String> IDENTIFIERS = Set.of("segmentId", "matchedSegmentId", "assetId", "kbId",
            "nextCursor", "segmentType", "assetType", "resultType");
    private static final Set<String> BODY_FIELDS = Set.of("content", "body", "snippet", "matchSnippet", "text");
    private static final Pattern LIST = Pattern.compile("^\\s*(?:[-+*]|\\d+[.)])\\s+.*");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+.*");

    static final class Unit {
        String id;
        final int start;
        int end;
        final List<Range> ranges = new ArrayList<>();
        final String source;
        Unit(String source, int start, int end) {
            this.source = source; this.start = start; this.end = end;
            ranges.add(new Range(start, end));
        }
        String text() { return source.substring(start, end); }
    }
    record Field(String pointer, List<Unit> units, String text) {}
    private final List<Field> fields = new ArrayList<>();
    private final Map<String, List<Unit>> groups = new LinkedHashMap<>();

    static EvidenceInspectionUnits collect(JsonNode input) {
        var result = new EvidenceInspectionUnits();
        result.visit(input, "", "");
        return result;
    }
    List<Field> fields() { return fields; }
    List<Unit> units() { return groups.values().stream().flatMap(List::stream).toList(); }

    void coalesce(int limit) {
        int count = units().size();
        while (count > limit) {
            List<Unit> chosen = null;
            int index = -1, shortest = Integer.MAX_VALUE;
            for (List<Unit> group : groups.values()) for (int i = 0; i + 1 < group.size(); i++) {
                Unit left = group.get(i), right = group.get(i + 1);
                int length = left.source.codePointCount(left.start, right.end);
                if (length < shortest) { chosen = group; index = i; shortest = length; }
            }
            if (chosen == null) throw new IllegalArgumentException("evidence_check_capacity_exceeded");
            Unit left = chosen.get(index), right = chosen.remove(index + 1);
            left.end = right.end; left.ranges.addAll(right.ranges); count--;
        }
    }

    JsonNode retain(JsonNode input, Map<String, String> decisions) {
        JsonNode cleaned = input.deepCopy();
        for (Field field : fields) {
            StringBuilder out = new StringBuilder();
            int offset = 0;
            for (Unit unit : field.units()) {
                out.append(field.text(), offset, unit.start);
                if ("KEEP".equals(decisions.get(unit.id))) out.append(unit.text());
                offset = unit.end;
            }
            out.append(field.text(), offset, field.text().length());
            int slash = field.pointer().lastIndexOf('/');
            if (slash < 0) { cleaned = TextNode.valueOf(out.toString()); continue; }
            JsonNode parent = cleaned.at(field.pointer().substring(0, slash));
            String key = field.pointer().substring(slash + 1).replace("~1", "/").replace("~0", "~");
            if (parent.isArray()) ((ArrayNode) parent).set(Integer.parseInt(key), TextNode.valueOf(out.toString()));
            else ((ObjectNode) parent).put(key, out.toString());
        }
        return cleaned;
    }

    private void visit(JsonNode node, String pointer, String key) {
        if (node.isTextual()) {
            String text = node.asText();
            if (IDENTIFIERS.contains(key) && text.matches("[A-Za-z0-9_:=/+.\\-]{0,256}")) return;
            boolean body = BODY_FIELDS.contains(key);
            // Identical complete fields share decisions, but separate fields are never concatenated.
            List<Unit> units = groups.computeIfAbsent((body ? "body:" : "metadata:") + text,
                    ignored -> body ? partition(text) : metadata(text));
            fields.add(new Field(pointer, units, text));
        } else if (node.isObject()) node.fields().forEachRemaining(e -> visit(e.getValue(),
                pointer + "/" + e.getKey().replace("~", "~0").replace("/", "~1"), e.getKey()));
        else if (node.isArray()) for (int i = 0; i < node.size(); i++) visit(node.get(i), pointer + "/" + i, key);
    }

    private static List<Unit> metadata(String text) {
        List<Unit> units = new ArrayList<>();
        if (!text.isBlank()) units.add(new Unit(text, 0, text.length()));
        return units;
    }

    private static List<Unit> partition(String text) {
        List<Unit> units = new ArrayList<>();
        int offset = 0, start = -1;
        String kind = "", fence = "";
        for (String line : text.split("(?<=\\n)", -1)) {
            String trimmed = line.strip();
            String nextKind = trimmed.isEmpty() ? "blank" : HEADING.matcher(line.stripTrailing()).matches() ? "heading"
                    : LIST.matcher(line.stripTrailing()).matches() ? "list" : trimmed.startsWith("|") ? "table" : "text";
            if (!fence.isEmpty()) {
                offset += line.length();
                if (trimmed.matches(Pattern.quote(fence.substring(0, 1)) + "{" + fence.length() + ",}\\s*")) {
                    units.add(new Unit(text, start, offset)); start = -1; fence = ""; kind = "";
                }
                continue;
            }
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                if (start >= 0) units.add(new Unit(text, start, offset));
                start = offset; char marker = trimmed.charAt(0); int n = 0;
                while (n < trimmed.length() && trimmed.charAt(n) == marker) n++;
                fence = trimmed.substring(0, n); kind = "fence";
            } else if (nextKind.equals("blank")) {
                if (start >= 0) units.add(new Unit(text, start, offset));
                start = -1; kind = "";
            } else {
                boolean continuation = (kind.equals("list") && !nextKind.equals("heading") && line.startsWith("  "));
                if (start >= 0 && ((!nextKind.equals(kind) && !continuation) || nextKind.equals("heading"))) {
                    units.add(new Unit(text, start, offset)); start = -1;
                }
                if (start < 0) { start = offset; kind = nextKind; }
            }
            offset += line.length();
        }
        if (start >= 0) units.add(new Unit(text, start, text.length()));
        return units;
    }
}
