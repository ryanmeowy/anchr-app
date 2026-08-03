package com.anchr.core.search.infrastructure.persistence.es;

import co.elastic.clients.elasticsearch.indices.AliasDefinition;
import co.elastic.clients.elasticsearch.indices.get_alias.IndexAliases;
import org.junit.jupiter.api.Test;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentIndexAliasManagerTest {

    private static final String READ_ALIAS = "kb_segment_read";
    private static final String WRITE_ALIAS = "kb_segment_write";

    @Test
    void evaluateShouldAcceptUniqueAlignedAliasesWithExplicitWriteIndex() {
        var topology = SegmentIndexAliasManager.evaluate(
                Map.of("kb_segment_1", aliases(
                        READ_ALIAS, alias(null),
                        WRITE_ALIAS, alias(true))),
                READ_ALIAS,
                WRITE_ALIAS);

        assertTrue(topology.valid());
        assertTrue(topology.readable());
        assertTrue(topology.writable());
        assertEquals("kb_segment_1", topology.physicalIndex());
    }

    @Test
    void evaluateShouldKeepReadsAvailableWhenWriteAliasIsMissing() {
        var topology = SegmentIndexAliasManager.evaluate(
                Map.of("kb_segment_1", aliases(READ_ALIAS, alias(null))),
                READ_ALIAS,
                WRITE_ALIAS);

        assertTrue(topology.readable());
        assertFalse(topology.writable());
        assertFalse(topology.valid());
        assertEquals("kb_segment_1", topology.readIndex());
    }

    @Test
    void evaluateShouldRejectReadAliasPointingToMultipleIndices() {
        var topology = SegmentIndexAliasManager.evaluate(
                Map.of(
                        "kb_segment_1", aliases(
                                READ_ALIAS, alias(null),
                                WRITE_ALIAS, alias(true)),
                        "kb_segment_2", aliases(READ_ALIAS, alias(null))),
                READ_ALIAS,
                WRITE_ALIAS);

        assertFalse(topology.readable());
        assertFalse(topology.writable());
        assertFalse(topology.valid());
    }

    @Test
    void evaluateShouldRejectWriteAliasWithoutExplicitWriteIndex() {
        var topology = SegmentIndexAliasManager.evaluate(
                Map.of("kb_segment_1", aliases(
                        READ_ALIAS, alias(null),
                        WRITE_ALIAS, alias(null))),
                READ_ALIAS,
                WRITE_ALIAS);

        assertTrue(topology.readable());
        assertFalse(topology.writable());
        assertFalse(topology.valid());
    }

    @Test
    void evaluateShouldRejectReadAndWriteAliasesPointingToDifferentIndices() {
        var topology = SegmentIndexAliasManager.evaluate(
                Map.of(
                        "kb_segment_1", aliases(READ_ALIAS, alias(null)),
                        "kb_segment_2", aliases(WRITE_ALIAS, alias(true))),
                READ_ALIAS,
                WRITE_ALIAS);

        assertTrue(topology.readable());
        assertFalse(topology.writable());
        assertFalse(topology.valid());
    }

    private IndexAliases aliases(String alias, AliasDefinition definition) {
        return IndexAliases.of(builder -> builder.aliases(alias, definition));
    }

    private IndexAliases aliases(
            String firstAlias,
            AliasDefinition firstDefinition,
            String secondAlias,
            AliasDefinition secondDefinition
    ) {
        return IndexAliases.of(builder -> builder
                .aliases(firstAlias, firstDefinition)
                .aliases(secondAlias, secondDefinition));
    }

    private AliasDefinition alias(Boolean writeIndex) {
        return AliasDefinition.of(builder -> builder.isWriteIndex(writeIndex));
    }
}
