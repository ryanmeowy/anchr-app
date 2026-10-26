package com.anchr.core.common.constant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SegmentIndexConstantTest {

    @AfterEach
    void clearOverride() {
        System.clearProperty(SegmentIndexConstant.INDEX_OVERRIDE_PROPERTY);
    }

    @Test
    void defaultsKeepTheCloudIndexNames() {
        Assumptions.assumeTrue(System.getenv(SegmentIndexConstant.INDEX_OVERRIDE_ENV) == null,
                "environment override is set in this shell");
        System.clearProperty(SegmentIndexConstant.INDEX_OVERRIDE_PROPERTY);
        assertEquals("kb_segment", SegmentIndexConstant.indexName());
        assertEquals("kb_segment_read", SegmentIndexConstant.readAlias());
        assertEquals("kb_segment_write", SegmentIndexConstant.writeAlias());
    }

    @Test
    void systemPropertyMovesEveryNameToTheOverriddenIndex() {
        System.setProperty(SegmentIndexConstant.INDEX_OVERRIDE_PROPERTY, "kb_segment_eval");
        assertEquals("kb_segment_eval", SegmentIndexConstant.indexName());
        assertEquals("kb_segment_eval_read", SegmentIndexConstant.readAlias());
        assertEquals("kb_segment_eval_write", SegmentIndexConstant.writeAlias());
    }

    @Test
    void blankPropertyFallsBackToDefaults() {
        Assumptions.assumeTrue(System.getenv(SegmentIndexConstant.INDEX_OVERRIDE_ENV) == null,
                "environment override is set in this shell");
        System.setProperty(SegmentIndexConstant.INDEX_OVERRIDE_PROPERTY, "   ");
        assertEquals("kb_segment", SegmentIndexConstant.indexName());
        assertEquals("kb_segment_read", SegmentIndexConstant.readAlias());
        assertEquals("kb_segment_write", SegmentIndexConstant.writeAlias());
    }
}
