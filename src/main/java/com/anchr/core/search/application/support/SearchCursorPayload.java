package com.anchr.core.search.application.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Cursor payload before signing.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchCursorPayload implements Serializable {

    private String sessionId;

    /**
     * Next offset to read in the session snapshot.
     */
    private int offset;

    private String queryFingerprint;

    private long expireAt;
}
