package com.anchr.core.kb.domain.model;

/**
 * Logical asset-index changes consumed by reconciliation and index rebuilds.
 */
public enum AssetIndexChangeOperation {
    GENERATION_ACTIVATED,
    ASSET_DELETED
}
