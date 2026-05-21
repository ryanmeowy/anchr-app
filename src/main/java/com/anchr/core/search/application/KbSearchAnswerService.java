package com.anchr.core.search.application;

import com.anchr.core.search.interfaces.rest.dto.KbAnswerDTO;
import com.anchr.core.search.interfaces.rest.dto.KbSearchQueryDTO;

/**
 * Builds grounded answers for search results.
 */
public interface KbSearchAnswerService {

    KbAnswerDTO answer(KbSearchQueryDTO query);
}
