package com.smart.vision.core.integration.multimodal.embedding;

import com.smart.vision.core.common.config.EmbeddingProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class EmbeddingBackendRegistry {

    private final EmbeddingProperties properties;
    private final Map<String, EmbeddingBackend> backends;

    public EmbeddingBackendRegistry(EmbeddingProperties properties, List<EmbeddingBackend> backendList) {
        this.properties = properties;
        this.backends = indexBackends(backendList);
        getSelected();
    }

    public EmbeddingBackend getSelected() {
        String backendName = normalize(properties.getBackend());
        EmbeddingBackend backend = backends.get(backendName);
        if (backend == null) {
            throw new IllegalStateException("Embedding backend '" + properties.getBackend()
                    + "' is not available. Available backends: " + backends.keySet());
        }
        return backend;
    }

    private Map<String, EmbeddingBackend> indexBackends(List<EmbeddingBackend> backendList) {
        Map<String, EmbeddingBackend> indexed = new LinkedHashMap<>();
        for (EmbeddingBackend backend : backendList) {
            String name = normalize(backend.backendName());
            if (!StringUtils.hasText(name)) {
                throw new IllegalStateException("Embedding backend name must not be blank: " + backend.getClass().getName());
            }
            EmbeddingBackend previous = indexed.putIfAbsent(name, backend);
            if (previous != null) {
                throw new IllegalStateException("Duplicate embedding backend name '" + name + "': "
                        + previous.getClass().getName() + ", " + backend.getClass().getName());
            }
        }
        return Map.copyOf(indexed);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
