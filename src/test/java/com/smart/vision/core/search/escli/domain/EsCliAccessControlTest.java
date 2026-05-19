package com.smart.vision.core.search.escli.domain;

import com.smart.vision.core.common.config.VectorConfig;
import com.smart.vision.core.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EsCliAccessControlTest {

    @Test
    void isAllowed_shouldUseConfiguredPatterns() {
        EsCliAccessConfig config = new EsCliAccessConfig();
        config.setAllowedIndexPatterns(List.of("smart_gallery_*", "logs-*"));

        VectorConfig vectorConfig = new VectorConfig();
        EsCliAccessControl control = new EsCliAccessControl(config, vectorConfig);

        assertThat(control.isAllowed("smart_gallery_local_v2")).isTrue();
        assertThat(control.isAllowed("logs-2026-04")).isTrue();
        assertThat(control.isAllowed("other-index")).isFalse();
    }

    @Test
    void isAllowed_shouldFallbackToVectorDefaultsWhenNotConfigured() {
        EsCliAccessConfig config = new EsCliAccessConfig();

        VectorConfig vectorConfig = new VectorConfig();
        vectorConfig.setReadAlias("smart_gallery_local_read");
        vectorConfig.setWriteAlias("smart_gallery_local_write");
        vectorConfig.setIndexName("smart_gallery_local");
        vectorConfig.setIndexVersion("v3");
        vectorConfig.setDimension(1024);
        vectorConfig.setEmbeddingBackend("aliyun");
        vectorConfig.setEmbeddingModel("multimodal-embedding-v1");
        vectorConfig.setPreprocessVersion("v1");

        EsCliAccessControl control = new EsCliAccessControl(config, vectorConfig);

        assertThat(control.isAllowed("smart_gallery_local_read")).isTrue();
        assertThat(control.isAllowed(vectorConfig.getPhysicalIndexName())).isTrue();
        assertThat(control.isAllowed("random-index")).isFalse();
    }

    @Test
    void assertIndexAllowed_shouldThrowWhenDenied() {
        EsCliAccessConfig config = new EsCliAccessConfig();
        config.setAllowedIndexPatterns(List.of("smart_gallery_*"));

        EsCliAccessControl control = new EsCliAccessControl(config, new VectorConfig());

        assertThatThrownBy(() -> control.assertIndexAllowed("secret-index"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Index access denied");
    }
}
