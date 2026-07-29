package com.anchr.core.activity.application.api;

import com.anchr.core.activity.application.api.model.ActivityQueryResult;

import java.util.Optional;

/** Narrow read capabilities exposed by Activity. */
public interface ActivityQueryApi {

    ActivityQueryResult.Page<ActivityQueryResult.Question> recentQuestions(Integer limit, String cursor);

    ActivityQueryResult.Page<ActivityQueryResult.Citation> recentCitations(Integer limit, String cursor);

    ActivityQueryResult.Page<ActivityQueryResult.Search> recentSearch(Integer limit, String cursor);

    ActivityQueryResult.Page<ActivityQueryResult.Document> recentDocument(Integer limit, String cursor);

    Optional<ActivityQueryResult.Citation> findCitationById(String id);
}
