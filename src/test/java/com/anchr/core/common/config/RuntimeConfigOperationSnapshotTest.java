package com.anchr.core.common.config;

import com.anchr.core.common.util.RuntimeConfigUnit;
import com.anchr.core.search.application.model.SearchRuntimeSettings;
import com.anchr.core.settings.application.api.RuntimeConfigQueryApi;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeConfigOperationSnapshotTest {

    @Test
    void shouldApplyAnUpdateToTheNextOperationWithoutMutatingTheCurrentSettings() {
        Map<String, String> stored = new HashMap<>();
        RuntimeConfigQueryApi api = (type, key) ->
                Optional.ofNullable(stored.get(type + "." + key.propertyName()));
        RuntimeConfigUnit unit = new RuntimeConfigUnit(api);

        SearchRuntimeSettings currentOperation =
                SearchRuntimeSettings.load(unit);
        stored.put("SEARCH.rankConstant", "80");
        SearchRuntimeSettings nextOperation =
                SearchRuntimeSettings.load(unit);

        assertThat(currentOperation.rankConstant()).isEqualTo(60);
        assertThat(nextOperation.rankConstant()).isEqualTo(80);
    }
}
