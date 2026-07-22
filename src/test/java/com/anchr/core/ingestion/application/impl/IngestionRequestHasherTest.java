package com.anchr.core.ingestion.application.impl;

import com.anchr.core.ingestion.application.IngestionApplicationService.IngestionCreateItemCommand;
import com.anchr.core.ingestion.domain.model.DedupeStrategy;
import com.anchr.core.ingestion.domain.model.IngestionSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionRequestHasherTest {

    @Test
    void hash_shouldBeVersionedAndDeterministic() {
        String first = hash("kb-1", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP, List.of(item("a")));
        String second = hash("kb-1", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP, List.of(item("a")));

        assertThat(first).isEqualTo(second).startsWith("v1:").hasSize(67);
        assertThat(first.substring(3)).matches("[0-9a-f]{64}");
    }

    @Test
    void hash_shouldCoverContextAndEveryPersistedAssetInput() {
        IngestionCreateItemCommand baseItem = item("a");
        String base = hash("kb-1", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP, List.of(baseItem));

        assertThat(List.of(
                hash("kb-2", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP, List.of(baseItem)),
                hash("kb-1", IngestionSourceType.URL, DedupeStrategy.SKIP, List.of(baseItem)),
                hash("kb-1", IngestionSourceType.UPLOAD, DedupeStrategy.VERSIONED, List.of(baseItem)),
                hash("kb-1", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP,
                        List.of(new IngestionCreateItemCommand("changed.pdf", "Title", "PDF", "application/pdf",
                                12L, "objects/a", "hash-a", "https://example.com/a"))),
                hash("kb-1", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP,
                        List.of(new IngestionCreateItemCommand("a.pdf", "Changed", "PDF", "application/pdf",
                                12L, "objects/a", "hash-a", "https://example.com/a"))),
                hash("kb-1", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP,
                        List.of(new IngestionCreateItemCommand("a.pdf", "Title", "DOCX", "application/pdf",
                                12L, "objects/a", "hash-a", "https://example.com/a"))),
                hash("kb-1", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP,
                        List.of(new IngestionCreateItemCommand("a.pdf", "Title", "PDF", "other/type",
                                12L, "objects/a", "hash-a", "https://example.com/a"))),
                hash("kb-1", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP,
                        List.of(new IngestionCreateItemCommand("a.pdf", "Title", "PDF", "application/pdf",
                                13L, "objects/a", "hash-a", "https://example.com/a"))),
                hash("kb-1", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP,
                        List.of(new IngestionCreateItemCommand("a.pdf", "Title", "PDF", "application/pdf",
                                12L, "objects/b", "hash-a", "https://example.com/a"))),
                hash("kb-1", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP,
                        List.of(new IngestionCreateItemCommand("a.pdf", "Title", "PDF", "application/pdf",
                                12L, "objects/a", "hash-b", "https://example.com/a"))),
                hash("kb-1", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP,
                        List.of(new IngestionCreateItemCommand("a.pdf", "Title", "PDF", "application/pdf",
                                12L, "objects/a", "hash-a", "https://example.com/b")))
        )).doesNotContain(base);
    }

    @Test
    void hash_shouldPreserveItemOrderAndFieldBoundaries() {
        IngestionCreateItemCommand first = item("a");
        IngestionCreateItemCommand second = item("b");

        assertThat(hash("kb-1", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP, List.of(first, second)))
                .isNotEqualTo(hash("kb-1", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP,
                        List.of(second, first)));
        assertThat(hash("kb-1", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP,
                List.of(new IngestionCreateItemCommand("ab", "c", "PDF", null, null, null, null, null))))
                .isNotEqualTo(hash("kb-1", IngestionSourceType.UPLOAD, DedupeStrategy.SKIP,
                        List.of(new IngestionCreateItemCommand("a", "bc", "PDF", null, null, null, null, null))));
    }

    private String hash(String kbId, IngestionSourceType sourceType, DedupeStrategy strategy,
                        List<IngestionCreateItemCommand> items) {
        return IngestionRequestHasher.hash(kbId, sourceType, strategy, items);
    }

    private IngestionCreateItemCommand item(String suffix) {
        return new IngestionCreateItemCommand(
                suffix + ".pdf", "Title", "PDF", "application/pdf", 12L,
                "objects/" + suffix, "hash-" + suffix, "https://example.com/" + suffix);
    }
}
