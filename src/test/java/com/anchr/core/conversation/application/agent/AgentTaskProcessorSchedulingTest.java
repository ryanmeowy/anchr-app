package com.anchr.core.conversation.application.agent;

import com.anchr.core.conversation.application.AgentRuntimeSnapshotService;
import com.anchr.core.testsupport.RuntimeConfigTestUnits;
import com.anchr.core.conversation.domain.model.AgentTask;
import com.anchr.core.conversation.domain.repository.AgentTaskRepository;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentTaskProcessorSchedulingTest {

    @Test
    void claimsOnlyAfterTheExecutorStartsTheWorker() {
        TestTaskRepository repository = new TestTaskRepository();
        AtomicReference<Runnable> worker = new AtomicReference<>();
        AgentTaskProcessor processor = processor(repository, worker::set);

        processor.trigger("task-1");

        assertThat(repository.claims).hasValue(0);
        assertThat(worker.get()).isNotNull();

        worker.get().run();

        assertThat(repository.claims).hasValue(1);
        assertThat(repository.lastClaimedTaskId).isEqualTo("task-1");
    }

    @Test
    void deduplicatesQueuedWorkAndAllowsSchedulingAgainAfterItFinishes() {
        TestTaskRepository repository = new TestTaskRepository();
        AtomicInteger submissions = new AtomicInteger();
        AtomicReference<Runnable> worker = new AtomicReference<>();
        Executor executor = command -> {
            submissions.incrementAndGet();
            worker.set(command);
        };
        AgentTaskProcessor processor = processor(repository, executor);

        processor.trigger("task-1");
        processor.trigger("task-1");
        assertThat(submissions).hasValue(1);

        worker.get().run();
        processor.trigger("task-1");
        assertThat(submissions).hasValue(2);
    }

    @Test
    void rejectedSchedulingLeavesTheTaskUnclaimedAndRetryable() {
        TestTaskRepository repository = new TestTaskRepository();
        AtomicInteger submissions = new AtomicInteger();
        Executor executor = command -> {
            if (submissions.getAndIncrement() == 0) throw new IllegalStateException("rejected");
        };
        AgentTaskProcessor processor = processor(repository, executor);

        processor.trigger("task-1");
        processor.trigger("task-1");

        assertThat(submissions).hasValue(2);
        assertThat(repository.claims).hasValue(0);
    }

    private AgentTaskProcessor processor(AgentTaskRepository repository, Executor executor) {
        return new AgentTaskProcessor(
                repository,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                RuntimeConfigTestUnits.defaults(),
                null,
                null,
                executor,
                mock(AgentRuntimeSnapshotService.class));
    }

    private static final class TestTaskRepository implements AgentTaskRepository {
        private final AtomicInteger claims = new AtomicInteger();
        private String lastClaimedTaskId;

        @Override public boolean claim(String taskId, String owner, long now, long leaseUntil) {
            lastClaimedTaskId = taskId;
            claims.incrementAndGet();
            return false;
        }

        @Override public void save(AgentTask task) { }
        @Override public Optional<AgentTask> findById(String taskId) { return Optional.empty(); }
        @Override public List<AgentTask> findByIds(Collection<String> taskIds) {
            return List.of();
        }
        @Override public List<AgentTask> findBySessionId(String sessionId) { return List.of(); }
        @Override public List<AgentTask> findClaimable(long now, int limit) { return List.of(); }
        @Override public boolean saveClaimed(AgentTask task, String expectedLeaseOwner) { return false; }
        @Override public boolean cancel(String taskId, String userId, long now) { return false; }
        @Override public void deleteBySessionId(String sessionId) { }
    }
}
