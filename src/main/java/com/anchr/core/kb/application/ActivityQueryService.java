package com.anchr.core.kb.application;

import com.anchr.core.kb.interfaces.rest.dto.RecentCitationListDTO;
import com.anchr.core.kb.interfaces.rest.dto.RecentDocumentListDTO;
import com.anchr.core.kb.interfaces.rest.dto.RecentQuestionListDTO;
import com.anchr.core.kb.interfaces.rest.dto.RecentSearchListDTO;

/**
 * Query service for recent Ask First activities.
 */
public interface ActivityQueryService {

    RecentQuestionListDTO recentQuestions(Integer limit, String cursor);

    RecentCitationListDTO recentCitations(Integer limit, String cursor);

    RecentSearchListDTO recentSearch(Integer limit, String cursor);

    RecentDocumentListDTO recentDocument(Integer limit, String cursor);
}
