package com.anchr.core.conversation.application;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalAnswerEventBrokerTest {

    @Test
    void lateSubscriberReceivesCurrentSnapshotThenContinuesWithMonotonicSequence() {
        LocalAnswerEventBroker broker = new LocalAnswerEventBroker();
        AnswerIdentity identity = identity(1);
        broker.started(identity);
        broker.delta(identity, "已生成");

        List<AnswerEvent> received = new ArrayList<>();
        AnswerEventBroker.Subscription subscription = broker.subscribe("task-1", received::add);
        broker.delta(identity, "正文");

        assertThat(received).extracting(AnswerEvent::type)
                .containsExactly(AnswerEvent.Type.SNAPSHOT, AnswerEvent.Type.DELTA);
        assertThat(received).extracting(AnswerEvent::sequence).containsExactly(2L, 3L);
        assertThat(received.getFirst().text()).isEqualTo("已生成");
        assertThat(received.getLast().text()).isEqualTo("正文");
        subscription.close();
    }

    @Test
    void newerRevisionClearsProvisionalStateAndRejectsOldRevisionEvents() {
        LocalAnswerEventBroker broker = new LocalAnswerEventBroker();
        List<AnswerEvent> received = new ArrayList<>();
        broker.subscribe("task-1", received::add);

        broker.started(identity(1));
        broker.delta(identity(1), "旧内容");
        broker.started(identity(2));
        broker.delta(identity(1), "迟到内容");
        broker.delta(identity(2), "新内容");

        assertThat(received).extracting(AnswerEvent::type).containsExactly(
                AnswerEvent.Type.STARTED,
                AnswerEvent.Type.DELTA,
                AnswerEvent.Type.STARTED,
                AnswerEvent.Type.DELTA);
        assertThat(received.getLast().identity().revision()).isEqualTo(2);
        assertThat(received.getLast().sequence()).isEqualTo(2);
        assertThat(received).extracting(AnswerEvent::text).doesNotContain("迟到内容");
    }

    @Test
    void terminalEventRejectsLaterEventsInTheSameRevision() {
        LocalAnswerEventBroker broker = new LocalAnswerEventBroker();
        List<AnswerEvent> received = new ArrayList<>();
        broker.subscribe("task-1", received::add);
        AnswerIdentity identity = identity(1);

        broker.snapshot(identity, "最终答案");
        broker.completed(identity);
        broker.delta(identity, "迟到内容");

        assertThat(received).extracting(AnswerEvent::type)
                .containsExactly(AnswerEvent.Type.SNAPSHOT, AnswerEvent.Type.COMPLETED);
    }

    private AnswerIdentity identity(long revision) {
        return new AnswerIdentity(
                "task-1", "turn-1", "session-1", "task-1", "run-1", revision);
    }
}
