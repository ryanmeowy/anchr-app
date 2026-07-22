package com.anchr.core.ingestion.application.impl;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IngestionCreateTransactionRunnerTest {

    @Test
    void writeAndRead_shouldAlwaysUseIndependentTransactions() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        IngestionCreateTransactionRunner runner = new IngestionCreateTransactionRunner(manager);

        assertThat(runner.write(() -> "created")).isEqualTo("created");
        assertThat(runner.read(() -> "winner")).isEqualTo("winner");

        assertThat(manager.definitions)
                .extracting(TransactionSettings::propagation)
                .containsExactly(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(manager.definitions)
                .extracting(TransactionSettings::readOnly)
                .containsExactly(false, true);
        assertThat(manager.commits).isEqualTo(2);
        assertThat(manager.rollbacks).isZero();
    }

    @Test
    void callbackFailure_shouldRollbackAndNullResultShouldBeRejected() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        IngestionCreateTransactionRunner runner = new IngestionCreateTransactionRunner(manager);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> runner.write(() -> {
                    throw new IllegalStateException("failed");
                }));
        IllegalStateException empty = assertThrows(IllegalStateException.class,
                () -> runner.read(() -> null));

        assertThat(failure).hasMessage("failed");
        assertThat(empty).hasMessage("Ingestion transaction returned no result.");
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(manager.commits).isEqualTo(1);
    }

    private record TransactionSettings(int propagation, boolean readOnly) {
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        private final List<TransactionSettings> definitions = new ArrayList<>();
        private int commits;
        private int rollbacks;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            definitions.add(new TransactionSettings(
                    definition.getPropagationBehavior(), definition.isReadOnly()));
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            commits++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbacks++;
        }
    }
}
