package com.anchr.core.conversation.application;

import com.anchr.core.conversation.domain.model.ConversationCitation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory answer broker for one JVM only. Cross-instance live delivery is intentionally not
 * guaranteed; persisted Task/Turn state and polling/runtime-snapshot recovery remain authoritative.
 */
@Slf4j
@Component
public class LocalAnswerEventBroker implements AnswerEventBroker {
    private final Map<String, ChannelState> channels = new ConcurrentHashMap<>();

    @Override
    public Subscription subscribe(String channelId, Consumer<AnswerEvent> consumer) {
        if (!StringUtils.hasText(channelId) || consumer == null) return () -> { };
        ChannelState state = channels.computeIfAbsent(channelId, ignored -> new ChannelState());
        state.subscribers.add(consumer);
        AnswerEvent replay = state.replaySnapshot();
        if (replay != null) deliver(consumer, replay);
        return () -> {
            state.subscribers.remove(consumer);
            if (state.terminal && state.subscribers.isEmpty()) channels.remove(channelId, state);
        };
    }

    @Override
    public void started(AnswerIdentity identity) {
        publish(identity, AnswerEvent.Type.STARTED, null, null, "", List.of(), null);
    }

    @Override
    public void progress(AnswerIdentity identity, String stage, int progress) {
        publish(identity, AnswerEvent.Type.PROGRESS, stage, progress, null, List.of(), null);
    }

    @Override
    public void delta(AnswerIdentity identity, String text) {
        if (StringUtils.hasText(text)) {
            publish(identity, AnswerEvent.Type.DELTA, null, null, text, List.of(), null);
        }
    }

    @Override
    public void snapshot(AnswerIdentity identity, String canonicalAnswer) {
        publish(identity, AnswerEvent.Type.SNAPSHOT, null, null,
                canonicalAnswer == null ? "" : canonicalAnswer, List.of(), null);
    }

    @Override
    public void citations(AnswerIdentity identity, List<ConversationCitation> citations) {
        publish(identity, AnswerEvent.Type.CITATIONS, null, null, null, citations, null);
    }

    @Override
    public void completed(AnswerIdentity identity) {
        publish(identity, AnswerEvent.Type.COMPLETED, null, null, null, List.of(), null);
    }

    @Override
    public void failed(AnswerIdentity identity, String errorCode) {
        publish(identity, AnswerEvent.Type.FAILED, null, null, null, List.of(), errorCode);
    }

    @Override
    public void cancelled(AnswerIdentity identity) {
        publish(identity, AnswerEvent.Type.CANCELLED, null, null, null, List.of(), null);
    }

    private void publish(AnswerIdentity identity,
                         AnswerEvent.Type type,
                         String stage,
                         Integer progress,
                         String text,
                         List<ConversationCitation> citations,
                         String errorCode) {
        if (identity == null) return;
        ChannelState state = channels.computeIfAbsent(identity.channelId(), ignored -> new ChannelState());
        AnswerEvent event = state.next(identity, type, stage, progress, text, citations, errorCode);
        if (event == null) return;
        state.subscribers.forEach(consumer -> deliver(consumer, event));
        if (state.terminal && state.subscribers.isEmpty()) channels.remove(identity.channelId(), state);
    }

    private void deliver(Consumer<AnswerEvent> consumer, AnswerEvent event) {
        try {
            consumer.accept(event);
        } catch (RuntimeException exception) {
            log.debug("Answer event subscriber rejected event, channelId={}, type={}",
                    event.identity().channelId(), event.type(), exception);
        }
    }

    private static final class ChannelState {
        private final CopyOnWriteArrayList<Consumer<AnswerEvent>> subscribers = new CopyOnWriteArrayList<>();
        private AnswerIdentity identity;
        private long sequence;
        private String provisional = "";
        private boolean terminal;

        synchronized AnswerEvent next(AnswerIdentity nextIdentity,
                                      AnswerEvent.Type type,
                                      String stage,
                                      Integer progress,
                                      String text,
                                      List<ConversationCitation> citations,
                                      String errorCode) {
            if (identity == null || nextIdentity.revision() > identity.revision()) {
                identity = nextIdentity;
                sequence = 0;
                provisional = "";
                terminal = false;
            } else if (nextIdentity.revision() < identity.revision() || terminal) {
                return null;
            } else {
                identity = nextIdentity;
            }
            long nextSequence = ++sequence;
            if (type == AnswerEvent.Type.DELTA) provisional += text;
            if (type == AnswerEvent.Type.SNAPSHOT || type == AnswerEvent.Type.STARTED) {
                provisional = text == null ? "" : text;
            }
            if (type == AnswerEvent.Type.COMPLETED
                    || type == AnswerEvent.Type.FAILED
                    || type == AnswerEvent.Type.CANCELLED) terminal = true;
            return new AnswerEvent(type, identity, nextSequence, stage, progress,
                    text, citations, errorCode);
        }

        synchronized AnswerEvent replaySnapshot() {
            if (identity == null || !StringUtils.hasText(provisional)) return null;
            return new AnswerEvent(AnswerEvent.Type.SNAPSHOT, identity, sequence,
                    null, null, provisional, List.of(), null);
        }
    }
}
