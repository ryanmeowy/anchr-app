package com.anchr.core.search.infrastructure.persistence.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.get_alias.IndexAliases;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.anchr.core.common.constant.SegmentIndexConstant.READ_ALIAS;
import static com.anchr.core.common.constant.SegmentIndexConstant.WRITE_ALIAS;

@Slf4j
@Component
@RequiredArgsConstructor
public class SegmentIndexAliasManager {

    private final ElasticsearchClient esClient;

    public AliasTopology inspect() {
        try {
            boolean readExists = esClient.indices()
                    .existsAlias(e -> e.name(READ_ALIAS))
                    .value();
            boolean writeExists = esClient.indices()
                    .existsAlias(e -> e.name(WRITE_ALIAS))
                    .value();
            if (!readExists && !writeExists) {
                return evaluate(Map.of(), READ_ALIAS, WRITE_ALIAS);
            }
            List<String> existingAliases = readExists && writeExists
                    ? List.of(READ_ALIAS, WRITE_ALIAS)
                    : List.of(readExists ? READ_ALIAS : WRITE_ALIAS);
            Map<String, IndexAliases> aliases = esClient.indices()
                    .getAlias(a -> a.name(existingAliases))
                    .result();
            return evaluate(aliases, READ_ALIAS, WRITE_ALIAS);
        } catch (Exception e) {
            log.warn("Failed to inspect segment aliases: {}", e.getMessage());
            return AliasTopology.unavailable(e.getMessage());
        }
    }

    public AliasTopology requireValid() {
        AliasTopology topology = inspect();
        if (!topology.valid()) {
            throw new IllegalStateException("Invalid segment alias topology: " + topology.error());
        }
        return topology;
    }

    public void bindAliases(String physicalIndex) throws Exception {
        AliasTopology current = inspect();
        if (!current.querySucceeded()) {
            throw new IllegalStateException("Cannot inspect segment aliases: " + current.error());
        }
        if (current.readAliasPresent() || current.writeAliasPresent()) {
            if (current.valid() && physicalIndex.equals(current.physicalIndex())) {
                return;
            }
            throw new IllegalStateException(
                    "Refusing to bind aliases over existing topology: " + current.error());
        }

        esClient.indices().updateAliases(u -> u
                .actions(a -> a.add(add -> add.index(physicalIndex).alias(READ_ALIAS)))
                .actions(a -> a.add(add -> add
                        .index(physicalIndex)
                        .alias(WRITE_ALIAS)
                        .isWriteIndex(true))));
        AliasTopology updated = requireValid();
        if (!physicalIndex.equals(updated.physicalIndex())) {
            throw new IllegalStateException(
                    "Alias binding verification failed: expected " + physicalIndex
                            + ", actual " + updated.physicalIndex());
        }
    }

    public void switchAliases(String oldIndex, String newIndex) throws Exception {
        AliasTopology current = requireValid();
        if (!oldIndex.equals(current.physicalIndex())) {
            throw new IllegalStateException(
                    "Alias switch source changed: expected " + oldIndex
                            + ", actual " + current.physicalIndex());
        }

        try {
            esClient.indices().updateAliases(u -> u
                    .actions(a -> a.remove(r -> r.index(oldIndex).alias(READ_ALIAS)))
                    .actions(a -> a.add(add -> add.index(newIndex).alias(READ_ALIAS)))
                    .actions(a -> a.remove(r -> r.index(oldIndex).alias(WRITE_ALIAS)))
                    .actions(a -> a.add(add -> add
                            .index(newIndex)
                            .alias(WRITE_ALIAS)
                            .isWriteIndex(true))));
        } catch (Exception e) {
            AliasTopology afterFailure = inspect();
            if (afterFailure.valid() && newIndex.equals(afterFailure.physicalIndex())) {
                log.warn("Alias switch returned an error but verification shows it completed: {}",
                        e.getMessage());
                return;
            }
            throw e;
        }

        AliasTopology updated = requireValid();
        if (!newIndex.equals(updated.physicalIndex())) {
            throw new IllegalStateException(
                    "Alias switch verification failed: expected " + newIndex
                            + ", actual " + updated.physicalIndex());
        }
    }

    static AliasTopology evaluate(
            Map<String, IndexAliases> indices,
            String readAlias,
            String writeAlias
    ) {
        List<String> readTargets = indices.entrySet().stream()
                .filter(entry -> entry.getValue().aliases().containsKey(readAlias))
                .map(Map.Entry::getKey)
                .toList();
        List<String> writeTargets = indices.entrySet().stream()
                .filter(entry -> entry.getValue().aliases().containsKey(writeAlias))
                .map(Map.Entry::getKey)
                .toList();
        List<String> explicitWriteTargets = indices.entrySet().stream()
                .filter(entry -> {
                    var definition = entry.getValue().aliases().get(writeAlias);
                    return definition != null && Boolean.TRUE.equals(definition.isWriteIndex());
                })
                .map(Map.Entry::getKey)
                .toList();

        boolean readable = readTargets.size() == 1;
        boolean writeAliasValid = writeTargets.size() == 1
                && explicitWriteTargets.size() == 1
                && writeTargets.getFirst().equals(explicitWriteTargets.getFirst());
        boolean aligned = readable
                && writeAliasValid
                && readTargets.getFirst().equals(writeTargets.getFirst());
        String error = aligned
                ? null
                : "readTargets=" + readTargets
                        + ", writeTargets=" + writeTargets
                        + ", explicitWriteTargets=" + explicitWriteTargets;
        return new AliasTopology(
                true,
                !readTargets.isEmpty(),
                !writeTargets.isEmpty(),
                readable,
                aligned,
                readable ? readTargets.getFirst() : null,
                writeAliasValid ? writeTargets.getFirst() : null,
                error);
    }

    public record AliasTopology(
            boolean querySucceeded,
            boolean readAliasPresent,
            boolean writeAliasPresent,
            boolean readable,
            boolean writable,
            String readIndex,
            String writeIndex,
            String error
    ) {
        public static AliasTopology unavailable(String error) {
            return new AliasTopology(
                    false, false, false, false, false, null, null, error);
        }

        public boolean valid() {
            return querySucceeded
                    && readable
                    && writable
                    && readIndex != null
                    && readIndex.equals(writeIndex);
        }

        public String physicalIndex() {
            return valid() ? readIndex : null;
        }
    }
}
