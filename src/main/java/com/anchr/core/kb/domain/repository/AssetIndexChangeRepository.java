package com.anchr.core.kb.domain.repository;

import com.anchr.core.kb.domain.model.AssetIndexChange;

import java.util.List;

/**
 * Append-only persistence boundary for logical asset-index changes.
 */
public interface AssetIndexChangeRepository {

    void save(AssetIndexChange change);

    List<AssetIndexChange> listAfterRevision(long exclusiveRevision, int limit);

    long currentRevision();
}
