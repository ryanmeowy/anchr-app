package com.smart.vision.core.conversation.application.impl;

import com.smart.vision.core.conversation.domain.model.ConversationCitation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FollowUpQuestionServiceImplTest {

    private final FollowUpQuestionServiceImpl service = new FollowUpQuestionServiceImpl();

    @Test
    void generate_shouldReturnTwoToFourEvidenceRelatedSuggestions() {
        ConversationCitation citation1 = new ConversationCitation();
        citation1.setFileName("mysql-notes.pdf");
        citation1.setPageNo(12);
        citation1.setHitType("TEXT_CHUNK");
        citation1.setSnippet("InnoDB supports row-level locking.");

        ConversationCitation citation2 = new ConversationCitation();
        citation2.setFileName("mysql-diagram.png");
        citation2.setHitType("CAPTION");
        citation2.setSnippet("InnoDB architecture diagram");

        List<String> suggestions = service.generate(
                "那 InnoDB 呢",
                "mysql 架构中的 InnoDB 作用",
                List.of(citation1, citation2)
        );

        assertThat(suggestions.size()).isBetween(2, 4);
        assertThat(suggestions.getFirst()).contains("mysql-notes.pdf");
        assertThat(suggestions.stream().anyMatch(item -> item.contains("mysql"))).isTrue();
    }

    @Test
    void generate_shouldReturnEmptyWhenNoEvidence() {
        List<String> suggestions = service.generate(
                "那 InnoDB 呢",
                "mysql 架构中的 InnoDB 作用",
                List.of()
        );

        assertThat(suggestions).isEmpty();
    }
}
