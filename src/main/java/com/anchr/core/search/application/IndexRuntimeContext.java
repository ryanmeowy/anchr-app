package com.anchr.core.search.application;

import com.anchr.core.search.domain.model.IndexRuntimeSnapshot;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

/** Request-local carrier that keeps query embedding and ES reads on one physical index profile. */
@Component
public class IndexRuntimeContext {
    private final ThreadLocal<IndexRuntimeSnapshot> current = new ThreadLocal<>();

    public Optional<IndexRuntimeSnapshot> current() {
        return Optional.ofNullable(current.get());
    }

    public <T> T withSnapshot(IndexRuntimeSnapshot snapshot, Supplier<T> action) {
        IndexRuntimeSnapshot previous = current.get();
        current.set(snapshot);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                current.remove();
            } else {
                current.set(previous);
            }
        }
    }
}
