package com.anchr.core.search.application.api;

import com.anchr.core.search.application.api.model.RetrievalCitationReasonRequest;

import java.util.Map;

/** Provider API for user-facing explanations of selected retrieval evidence. */
public interface RetrievalCitationReasonApi {

    Map<String, String> generate(RetrievalCitationReasonRequest request);
}
