package com.anchr.core.conversation.application.impl;

import java.util.*;
import java.util.regex.*;

/** Pure normalization also used on streaming prefixes, making numbering independent of delta boundaries. */
final class TraditionalRagCitationNormalizer {
    private static final Pattern MARKER = Pattern.compile("\\[(\\d+(?:-\\d+)*)]");
    private static final Pattern PENDING = Pattern.compile("\\[\\d*(?:-\\d*)*$");
    record Result(String text, List<String> segmentIds, int invalidCount) {}

    static Result normalize(String text, List<String> assets, List<String> segments, boolean partial) {
        if (partial) {
            Matcher pending = PENDING.matcher(text);
            if (pending.find()) text = text.substring(0, pending.start());
        }
        Map<String, Integer> assetIndexes = new LinkedHashMap<>();
        Map<String, Integer> perAsset = new HashMap<>();
        Map<Integer, String> labels = new LinkedHashMap<>();
        Matcher matcher = MARKER.matcher(text);
        StringBuilder result = new StringBuilder();
        int invalid = 0;
        while (matcher.find()) {
            int index;
            try { index = Integer.parseInt(matcher.group(1)) - 1; }
            catch (NumberFormatException ignored) { index = -1; }
            String replacement = "";
            if (index < 0 || index >= segments.size()) {
                invalid++;
            } else {
                String label = labels.get(index);
                if (label == null) {
                    String asset = assets.get(index);
                    int assetIndex = assetIndexes.computeIfAbsent(asset, ignored -> assetIndexes.size() + 1);
                    label = assetIndex + "-" + perAsset.merge(asset, 1, Integer::sum);
                    labels.put(index, label);
                }
                replacement = "[" + label + "]";
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return new Result(result.toString(), labels.keySet().stream().map(segments::get).toList(), invalid);
    }
}
