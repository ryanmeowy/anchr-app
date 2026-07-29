package com.anchr.core.activity.application.api;

import com.anchr.core.activity.application.api.model.ActivityRecordCommand;

/** Narrow write capabilities exposed by Activity. */
public interface ActivityRecordApi {

    void recordQuestionAsked(ActivityRecordCommand.QuestionAsked command);

    void recordCitationOpened(ActivityRecordCommand.CitationOpened command);

    void recordDocumentImported(ActivityRecordCommand.DocumentImported command);

    void recordSearchExecuted(ActivityRecordCommand.SearchExecuted command);

    void deleteBySessionId(String sessionId);

    void deleteCitationOpenedByAssetId(String userId, String assetId);
}
