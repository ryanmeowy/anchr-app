package com.anchr.core.kb.application.acl;

import com.anchr.core.activity.application.api.ActivityRecordApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Knowledge-side adapter for synchronous Activity cleanup. */
@Component
@RequiredArgsConstructor
public class KnowledgeActivityAcl {

    private final ActivityRecordApi activityRecordApi;

    public void deleteCitationOpenedByAssetId(String userId, String assetId) {
        activityRecordApi.deleteCitationOpenedByAssetId(userId, assetId);
    }
}
