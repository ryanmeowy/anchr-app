package com.anchr.core.common.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ControllerAuthorizationContractTest {

    @Test
    void everyApiHandlerShouldDeclareAuthenticationOrAnonymousAccess() {
        List<String> violations = new ArrayList<>();
        int handlerCount = 0;

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        for (var candidate : scanner.findCandidateComponents("com.anchr.core")) {
            Class<?> controller = load(candidate.getBeanClassName());
            if (!isProductionClass(controller)) {
                continue;
            }
            for (Method method : ReflectionUtils.getUniqueDeclaredMethods(controller)) {
                if (AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class) == null) {
                    continue;
                }
                handlerCount++;
                validate(controller, method, violations);
            }
        }

        assertThat(handlerCount).isGreaterThan(0);
        assertThat(violations)
                .as("Every REST handler must explicitly require authentication or permit anonymous access")
                .isEmpty();
    }

    private boolean isProductionClass(Class<?> type) {
        var codeSource = type.getProtectionDomain().getCodeSource();
        return codeSource == null
                || !codeSource.getLocation().toExternalForm().contains("/test-classes/");
    }

    private void validate(Class<?> controller, Method method, List<String> violations) {
        PermitAll methodPermitAll =
                AnnotatedElementUtils.findMergedAnnotation(method, PermitAll.class);
        RequireAuth methodRequireAuth =
                AnnotatedElementUtils.findMergedAnnotation(method, RequireAuth.class);
        if (methodPermitAll != null || methodRequireAuth != null) {
            if (methodPermitAll != null && methodRequireAuth != null) {
                violations.add(label(controller, method) + " declares conflicting method rules");
            }
            return;
        }

        PermitAll typePermitAll =
                AnnotatedElementUtils.findMergedAnnotation(controller, PermitAll.class);
        RequireAuth typeRequireAuth =
                AnnotatedElementUtils.findMergedAnnotation(controller, RequireAuth.class);
        if (typePermitAll == null && typeRequireAuth == null) {
            violations.add(label(controller, method) + " has no authorization rule");
        } else if (typePermitAll != null && typeRequireAuth != null) {
            violations.add(label(controller, method) + " inherits conflicting type rules");
        }
    }

    private Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Failed to load REST controller " + className, e);
        }
    }

    private String label(Class<?> controller, Method method) {
        return controller.getName() + "#" + method.getName();
    }
}
