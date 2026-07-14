package com.anchr.core.search.application.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of search query keyword rewriting.
 */
@Data
public class SearchRewriteResult {

    private String originalQuery;
    private String rewrittenQuery;
    private List<String> keywords = new ArrayList<>();
    private String intent;
    private String intentCategory;
    private boolean fallbackUsed;
}
