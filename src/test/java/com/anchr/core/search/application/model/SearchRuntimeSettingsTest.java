package com.anchr.core.search.application.model;

import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.settings.domain.model.RuntimeConfigType;
import com.anchr.core.settings.domain.model.SearchRuntimeConfigKey;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRuntimeSettingsTest {

    @Test
    void defaultTextSimilarityKeepsTheVectorChannelUsable() {
        SearchRuntimeSettings settings =
                SearchRuntimeSettings.load(new RuntimeConfigUnit((type, key) -> Optional.empty()));

        assertThat(SearchRuntimeSettings.DEFAULT_TEXT_SIMILARITY).isEqualTo(0.45F);
        assertThat(settings.textSimilarity()).isEqualTo(0.45F);
        assertThat(SearchRuntimeSettings.defaults().textSimilarity()).isEqualTo(0.45F);
    }

    @Test
    void configuredTextSimilarityOverridesTheDefault() {
        RuntimeConfigUnit unit = new RuntimeConfigUnit((type, key) ->
                type == RuntimeConfigType.SEARCH && key == SearchRuntimeConfigKey.TEXT_SIMILARITY
                        ? Optional.of("0.62")
                        : Optional.empty());

        assertThat(SearchRuntimeSettings.load(unit).textSimilarity()).isEqualTo(0.62F);
    }
}
