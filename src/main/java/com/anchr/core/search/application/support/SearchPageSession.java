package com.anchr.core.search.application.support;

import com.anchr.core.search.interfaces.rest.dto.SearchResultDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Session snapshot used by paged search.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchPageSession implements Serializable {

    private String sessionId;

    private String queryFingerprint;

    private long expireAt;

    private List<SearchResultDTO> results = new ArrayList<>();
}
