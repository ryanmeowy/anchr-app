package com.anchr.core;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringBeanConstructorContractTest {

    @Test
    void productionSpringBeans_shouldExposeExactlyOneConstructor() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(true);
        List<String> violations = new ArrayList<>();

        for (var candidate : scanner.findCandidateComponents("com.anchr")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            int constructorCount = type.getDeclaredConstructors().length;
            if (constructorCount != 1) {
                violations.add(type.getName() + " has " + constructorCount + " constructors");
            }
        }

        assertThat(violations)
                .as("Spring beans must not expose alternate construction paths for tests")
                .isEmpty();
    }
}
