package com.anchr.core.activity.application;

import com.anchr.core.activity.interfaces.rest.dto.RecentCitationListDTO;
import com.anchr.core.activity.interfaces.rest.dto.RecentQuestionListDTO;

/**
 * Query service for recent Ask First activities.
 */
public interface ActivityQueryService {

    RecentQuestionListDTO recentQuestions(Integer limit, String cursor);

    RecentCitationListDTO recentCitations(Integer limit, String cursor);
}
