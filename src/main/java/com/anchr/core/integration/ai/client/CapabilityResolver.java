package com.anchr.core.integration.ai.client;

import com.anchr.core.settings.domain.model.CapabilityConfig;
import com.anchr.core.settings.domain.repository.CapabilityConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the active {@link CapabilityConfig} for a cache slot.
 *
 * <p>Slots are {@code GENERATION}, {@code RERANK}, {@code EMBEDDING}. The
 * {@code EMBEDDING} slot covers both the {@code EMBEDDING} and {@code MULTI_EMBEDDING}
 * capabilities (they are mutually exclusive); resolution prefers {@code EMBEDDING}
 * and falls back to {@code MULTI_EMBEDDING}, matching the historical adapter logic.
 */
@Component
@RequiredArgsConstructor
public class CapabilityResolver {

    public static final String SLOT_GENERATION = "GENERATION";
    public static final String SLOT_RERANK = "RERANK";
    public static final String SLOT_EMBEDDING = "EMBEDDING";

    private final CapabilityConfigRepository repository;

    public Optional<CapabilityConfig> activeForSlot(String slot) {
        return switch (slot) {
            case SLOT_GENERATION -> repository.findByCapability("GENERATION").stream().findFirst();
            case SLOT_RERANK -> repository.findByCapability("RERANK").stream().findFirst();
            case SLOT_EMBEDDING -> repository.findByCapability("EMBEDDING").stream().findFirst()
                    .or(() -> repository.findByCapability("MULTI_EMBEDDING").stream().findFirst());
            default -> Optional.empty();
        };
    }

    /**
     * Maps a capability to its cache slot. {@code EMBEDDING} and {@code MULTI_EMBEDDING}
     * both resolve to the {@code EMBEDDING} slot.
     */
    public static String slotFor(String capability) {
        return "MULTI_EMBEDDING".equals(capability) ? SLOT_EMBEDDING : capability;
    }
}
