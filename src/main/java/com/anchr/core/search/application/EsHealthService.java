package com.anchr.core.search.application;

import com.anchr.core.search.interfaces.rest.dto.EsHealthDTO;

/**
 * Service for querying Elasticsearch health information.
 */
public interface EsHealthService {

    /**
     * Retrieve ES cluster health, index stats, and node info.
     *
     * @return health DTO; never null. {@link EsHealthDTO#isConnected()} is false when ES is unreachable.
     */
    EsHealthDTO getEsHealth();
}
