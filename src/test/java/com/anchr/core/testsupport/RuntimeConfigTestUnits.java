package com.anchr.core.testsupport;

import com.anchr.core.common.util.RuntimeConfigUnit;

import java.util.Map;
import java.util.Optional;

public final class RuntimeConfigTestUnits {

    private RuntimeConfigTestUnits() {
    }

    public static RuntimeConfigUnit defaults() {
        return new RuntimeConfigUnit((type, key) -> Optional.empty());
    }

    public static RuntimeConfigUnit values(Map<String, String> values) {
        Map<String, String> snapshot = values == null ? Map.of() : Map.copyOf(values);
        return new RuntimeConfigUnit((type, key) ->
                Optional.ofNullable(snapshot.get(
                        type + "." + key.propertyName())));
    }
}
