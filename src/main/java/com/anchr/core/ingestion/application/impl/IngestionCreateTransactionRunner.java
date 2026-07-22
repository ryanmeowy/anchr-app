package com.anchr.core.ingestion.application.impl;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Isolates ingestion request creation and winner lookup from any caller transaction.
 *
 * <p>A concurrent idempotency loser must finish rolling back all assets, task items and
 * activity writes before it reads the committed winner. Both callbacks therefore use
 * independent transactions instead of inheriting a caller's repeatable-read snapshot.</p>
 */
@Component
class IngestionCreateTransactionRunner {

    private final TransactionTemplate writeTemplate;
    private final TransactionTemplate readTemplate;

    IngestionCreateTransactionRunner(PlatformTransactionManager transactionManager) {
        writeTemplate = new TransactionTemplate(transactionManager);
        writeTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        readTemplate = new TransactionTemplate(transactionManager);
        readTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readTemplate.setReadOnly(true);
    }

    <T> T write(Supplier<T> callback) {
        return requireResult(writeTemplate.execute(status -> callback.get()));
    }

    <T> T read(Supplier<T> callback) {
        return requireResult(readTemplate.execute(status -> callback.get()));
    }

    private <T> T requireResult(T result) {
        if (result == null) {
            throw new IllegalStateException("Ingestion transaction returned no result.");
        }
        return result;
    }
}
