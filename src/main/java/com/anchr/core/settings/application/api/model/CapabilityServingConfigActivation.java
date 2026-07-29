package com.anchr.core.settings.application.api.model;

/** Immutable activation command issued only after the Retrieval alias switch succeeds. */
public record CapabilityServingConfigActivation(Long configId, String capability) {
}
