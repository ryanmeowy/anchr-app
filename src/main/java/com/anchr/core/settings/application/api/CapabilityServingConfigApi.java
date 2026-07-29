package com.anchr.core.settings.application.api;

import com.anchr.core.settings.application.api.model.CapabilityServingConfigActivation;

/** Serving configuration activation capability exposed by Capability. */
public interface CapabilityServingConfigApi {

    void activate(CapabilityServingConfigActivation activation);
}
