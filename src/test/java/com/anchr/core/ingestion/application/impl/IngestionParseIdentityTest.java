package com.anchr.core.ingestion.application.impl;

import com.anchr.core.kb.domain.model.Asset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionParseIdentityTest {

    @Test
    void requestId_shouldSeparateBusinessParseAttempts() {
        assertThat(IngestionParseIdentity.requestId("task-1", "item-1", 1))
                .isEqualTo("task-1:item-1:1");
        assertThat(IngestionParseIdentity.requestId("task-1", "item-1", 2))
                .isEqualTo("task-1:item-1:2");
    }

    @Test
    void sourceRevision_shouldPreferContentHashOverMutableLocations() {
        Asset first = Asset.builder()
                .id("asset-1")
                .fileHash("ABC123")
                .objectKey("old/object.pdf")
                .sourceUrl("https://example.test/old.pdf")
                .build();
        Asset moved = first.toBuilder()
                .objectKey("new/object.pdf")
                .sourceUrl("https://example.test/new.pdf")
                .build();

        assertThat(IngestionParseIdentity.sourceRevision(first))
                .isEqualTo(IngestionParseIdentity.sourceRevision(moved))
                .startsWith("v1:")
                .hasSize(67);
    }

    @Test
    void sourceRevision_shouldChangeWhenStableSourceChanges() {
        Asset first = Asset.builder().id("asset-1").objectKey("one.pdf").build();
        Asset second = Asset.builder().id("asset-1").objectKey("two.pdf").build();

        assertThat(IngestionParseIdentity.sourceRevision(first))
                .isNotEqualTo(IngestionParseIdentity.sourceRevision(second));
    }
}
