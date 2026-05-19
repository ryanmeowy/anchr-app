package com.smart.vision.core.conversation.application.impl;

import com.smart.vision.core.conversation.application.FollowUpQuestionService;
import com.smart.vision.core.conversation.domain.model.ConversationCitation;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Default follow-up question service with evidence-focused templates.
 */
@Service
public class FollowUpQuestionServiceImpl implements FollowUpQuestionService {

    private static final int MIN_COUNT = 2;
    private static final int MAX_COUNT = 4;

    @Override
    public List<String> generate(String userQuery, String rewrittenQuery, List<ConversationCitation> citations) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }
        String focus = resolveFocus(rewrittenQuery, userQuery);
        String secondary = resolveSecondary(rewrittenQuery, focus);

        LinkedHashSet<String> suggestions = new LinkedHashSet<>();
        appendEvidenceSuggestions(suggestions, focus, citations);
        appendRelationSuggestion(suggestions, focus, secondary);
        appendImageSuggestion(suggestions, focus, citations);
        appendFallbackSuggestion(suggestions, focus);

        List<String> result = new ArrayList<>(suggestions);
        if (result.size() > MAX_COUNT) {
            result = result.subList(0, MAX_COUNT);
        }
        if (result.size() < MIN_COUNT) {
            return List.of();
        }
        return result;
    }

    private void appendEvidenceSuggestions(Set<String> suggestions, String focus, List<ConversationCitation> citations) {
        ConversationCitation first = citations.getFirst();
        String firstFile = normalizeFileName(first == null ? null : first.getFileName());
        if (StringUtils.hasText(firstFile)) {
            suggestions.add("《" + firstFile + "》里还有哪些和“" + focus + "”直接相关的内容？");
        }

        ConversationCitation withPage = findFirstWithPage(citations);
        if (withPage != null && StringUtils.hasText(normalizeFileName(withPage.getFileName()))) {
            suggestions.add("在《" + normalizeFileName(withPage.getFileName()) + "》第" + withPage.getPageNo()
                    + "页，关于“" + focus + "”还有哪些关键点？");
        }
    }

    private void appendRelationSuggestion(Set<String> suggestions, String focus, String secondary) {
        if (StringUtils.hasText(secondary)) {
            suggestions.add("“" + focus + "”和“" + secondary + "”之间的关系是什么？");
            return;
        }
        suggestions.add("“" + focus + "”在当前知识库中的核心机制是什么？");
    }

    private void appendImageSuggestion(Set<String> suggestions, String focus, List<ConversationCitation> citations) {
        boolean hasImageEvidence = citations.stream()
                .filter(item -> item != null)
                .map(ConversationCitation::getHitType)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .anyMatch(hitType -> hitType.startsWith("OCR") || hitType.startsWith("CAPTION") || hitType.startsWith("IMAGE"));
        if (hasImageEvidence) {
            suggestions.add("有没有“" + focus + "”对应的结构图或示意图可对照理解？");
        }
    }

    private void appendFallbackSuggestion(Set<String> suggestions, String focus) {
        suggestions.add("基于当前引用，哪些片段最能解释“" + focus + "”的实现细节？");
    }

    private ConversationCitation findFirstWithPage(List<ConversationCitation> citations) {
        for (ConversationCitation citation : citations) {
            if (citation == null || citation.getPageNo() == null || citation.getPageNo() <= 0) {
                continue;
            }
            return citation;
        }
        return null;
    }

    private String resolveFocus(String rewrittenQuery, String userQuery) {
        List<String> tokens = extractTokens(rewrittenQuery);
        if (tokens.isEmpty()) {
            tokens = extractTokens(userQuery);
        }
        return tokens.isEmpty() ? "当前主题" : tokens.getFirst();
    }

    private String resolveSecondary(String rewrittenQuery, String focus) {
        List<String> tokens = extractTokens(rewrittenQuery);
        for (String token : tokens) {
            if (!token.equals(focus)) {
                return token;
            }
        }
        return null;
    }

    private List<String> extractTokens(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        return Stream.of(text.trim().split("[，。！？、,.;:\\s]+"))
                .map(String::trim)
                .filter(token -> token.length() >= 2 && token.length() <= 24)
                .filter(token -> !"什么".equals(token) && !"哪些".equals(token) && !"如何".equals(token))
                .distinct()
                .limit(8)
                .toList();
    }

    private String normalizeFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        return fileName.trim();
    }
}
