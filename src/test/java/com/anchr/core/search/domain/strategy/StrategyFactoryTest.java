package com.anchr.core.search.domain.strategy;

import com.anchr.core.search.domain.model.ImageSearchResultDTO;
import com.anchr.core.search.interfaces.rest.dto.SearchQueryDTO;
import com.anchr.core.search.domain.model.StrategyTypeEnum;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategyFactoryTest {

    @AfterEach
    void tearDown() {
        StrategySelectionContext.clear();
    }

    @Test
    void getStrategy_shouldReturnRequestedStrategy_whenCodeIsValidAndRegistered() {
        RetrievalStrategy hybrid = new DummyStrategy(StrategyTypeEnum.HYBRID);
        RetrievalStrategy vector = new DummyStrategy(StrategyTypeEnum.VECTOR_ONLY);
        StrategyFactory factory = new StrategyFactory(List.of(hybrid, vector), new SimpleMeterRegistry());

        RetrievalStrategy strategy = factory.getStrategy(StrategyTypeEnum.VECTOR_ONLY.getCode());

        assertThat(strategy.getType()).isEqualTo(StrategyTypeEnum.VECTOR_ONLY);
        StrategySelectionContext.SelectionSnapshot snapshot = StrategySelectionContext.get().orElseThrow();
        assertThat(snapshot.getRequested()).isEqualTo(StrategyTypeEnum.VECTOR_ONLY.getCode());
        assertThat(snapshot.getEffective()).isEqualTo(StrategyTypeEnum.VECTOR_ONLY.getCode());
        assertThat(snapshot.isFallback()).isFalse();
        assertThat(snapshot.getReason()).isEqualTo("none");
    }

    @Test
    void getStrategy_shouldFallbackToHybrid_whenCodeIsInvalid() {
        RetrievalStrategy hybrid = new DummyStrategy(StrategyTypeEnum.HYBRID);
        StrategyFactory factory = new StrategyFactory(List.of(hybrid), new SimpleMeterRegistry());

        RetrievalStrategy strategy = factory.getStrategy("unknown");

        assertThat(strategy.getType()).isEqualTo(StrategyTypeEnum.HYBRID);
        StrategySelectionContext.SelectionSnapshot snapshot = StrategySelectionContext.get().orElseThrow();
        assertThat(snapshot.getRequested()).isEqualTo("unknown");
        assertThat(snapshot.getEffective()).isEqualTo("HYBRID");
        assertThat(snapshot.isFallback()).isTrue();
        assertThat(snapshot.getReason()).isEqualTo("invalid_strategy_code");
    }

    @Test
    void getStrategy_shouldFallbackToHybrid_whenStrategyNotRegistered() {
        RetrievalStrategy hybrid = new DummyStrategy(StrategyTypeEnum.HYBRID);
        StrategyFactory factory = new StrategyFactory(List.of(hybrid), new SimpleMeterRegistry());

        RetrievalStrategy strategy = factory.getStrategy(StrategyTypeEnum.TEXT_ONLY.getCode());

        assertThat(strategy.getType()).isEqualTo(StrategyTypeEnum.HYBRID);
        StrategySelectionContext.SelectionSnapshot snapshot = StrategySelectionContext.get().orElseThrow();
        assertThat(snapshot.getRequested()).isEqualTo(StrategyTypeEnum.TEXT_ONLY.getCode());
        assertThat(snapshot.getEffective()).isEqualTo("HYBRID");
        assertThat(snapshot.isFallback()).isTrue();
        assertThat(snapshot.getReason()).isEqualTo("strategy_not_registered");
    }

    @Test
    void constructor_shouldThrow_whenHybridRegisteredMultipleTimes() {
        RetrievalStrategy firstHybrid = new DummyStrategy(StrategyTypeEnum.HYBRID);
        RetrievalStrategy secondHybrid = new DummyStrategy(StrategyTypeEnum.HYBRID);
        assertThatThrownBy(() -> new StrategyFactory(List.of(firstHybrid, secondHybrid), new SimpleMeterRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate strategy type registration detected");
    }

    private static class DummyStrategy implements RetrievalStrategy {
        private final StrategyTypeEnum type;

        private DummyStrategy(StrategyTypeEnum type) {
            this.type = type;
        }

        @Override
        public List<ImageSearchResultDTO> search(SearchQueryDTO query, List<Float> queryVector) {
            return List.of();
        }

        @Override
        public StrategyTypeEnum getType() {
            return type;
        }
    }
}
