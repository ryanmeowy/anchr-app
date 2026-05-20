package com.anchr.core.search.domain.strategy;

import com.anchr.core.search.domain.model.StrategyTypeEnum;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.anchr.core.search.domain.model.StrategyTypeEnum.HYBRID;

/**
 * Strategy factory for retrieving appropriate retrieval strategies by type
 * This factory provides type-safe access to registered strategies and handles
 * fallback logic when specific strategies are not available.
 *
 * @author Ryan
 * @since 2025/12/15
 */
@Slf4j
@Component
public class StrategyFactory {

    private final Map<StrategyTypeEnum, RetrievalStrategy> strategies;
    private final MeterRegistry meterRegistry;

    public StrategyFactory(List<RetrievalStrategy> strategyList, MeterRegistry meterRegistry) {
        this.strategies = new EnumMap<>(StrategyTypeEnum.class);
        this.meterRegistry = meterRegistry;
        for (RetrievalStrategy strategy : strategyList) {
            if (null == strategy || null == strategy.getType()) {
                continue;
            }
            RetrievalStrategy previous = this.strategies.putIfAbsent(strategy.getType(), strategy);
            if (previous != null) {
                throw new IllegalStateException("Duplicate strategy type registration detected: " + strategy.getType());
            }
            log.info("Search strategy registered: {}", strategy.getType().getDesc());
        }
        validateRequiredStrategies();
    }

    private void validateRequiredStrategies() {
        for (StrategyTypeEnum type : StrategyTypeEnum.values()) {
            if (!strategies.containsKey(type)) {
                log.warn("Missing required search strategy: {}", type.getDesc());
            }
        }
    }

    /**
     * Get strategy by type
     *
     * @param searchType the type of strategy to retrieve
     * @return the retrieval strategy
     * @throws IllegalArgumentException if no strategy found for given type
     */
    public RetrievalStrategy getStrategy(String searchType) {
        String requested = searchType == null ? "null" : searchType;
        try {
            StrategyTypeEnum strategyType = StrategyTypeEnum.getByCode(searchType);
            if (strategyType == null) {
                return fallbackWithObservability(requested, HYBRID.name(), "invalid_strategy_code");
            }
            RetrievalStrategy retrievalStrategy = strategies.get(strategyType);
            if (retrievalStrategy == null) {
                return fallbackWithObservability(requested, HYBRID.name(), "strategy_not_registered");
            }
            meterRegistry.counter("smartvision.strategy.selection",
                    "requested", requested,
                    "effective", strategyType.getCode(),
                    "fallback", "false",
                    "reason", "none").increment();
            StrategySelectionContext.set(StrategySelectionContext.SelectionSnapshot.builder()
                    .requested(requested)
                    .effective(strategyType.getCode())
                    .fallback(false)
                    .reason("none")
                    .build());
            return retrievalStrategy;
        } catch (Exception e) {
            log.error("Failed to get search strategy, requested={}", requested, e);
            return fallbackWithObservability(requested, HYBRID.name(), "strategy_resolve_exception");
        }
    }

    private RetrievalStrategy fallbackWithObservability(String requested, String effective, String reason) {
        meterRegistry.counter("smartvision.strategy.selection",
                "requested", requested,
                "effective", effective,
                "fallback", "true",
                "reason", reason).increment();
        meterRegistry.counter("smartvision.strategy.fallback",
                "requested", requested,
                "effective", effective,
                "reason", reason).increment();
        StrategySelectionContext.set(StrategySelectionContext.SelectionSnapshot.builder()
                .requested(requested)
                .effective(effective)
                .fallback(true)
                .reason(reason)
                .build());
        log.warn("Strategy fallback happened, requested={}, effective={}, reason={}", requested, effective, reason);
        return strategies.get(HYBRID);
    }
}
