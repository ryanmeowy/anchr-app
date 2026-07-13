package com.anchr.core.kb.application;

import com.anchr.core.kb.interfaces.rest.dto.AssetPreviewDTO;

/**
 * Builds whole-document preview metadata for Library navigation.
 */
public interface AssetPreviewService {

    AssetPreviewDTO getPreview(String kbId, String assetId);
}
