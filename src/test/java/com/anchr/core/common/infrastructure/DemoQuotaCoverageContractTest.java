package com.anchr.core.common.infrastructure;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A visitor token can call anything annotated for USER or GUEST. Any write endpoint that reaches
 * a paid provider must therefore be metered, otherwise the quota stops meaning anything.
 *
 * <p>This mirrors {@link ControllerAuthorizationContractTest}: adding a new paid endpoint without
 * {@code @DemoQuota} fails here instead of quietly becoming free traffic.
 */
class DemoQuotaCoverageContractTest {

    /**
     * Write endpoints a visitor may call that do not reach a paid provider. Each entry needs a
     * reason; "it was convenient" is not one.
     */
    private static final Set<String> FREE_ALLOWED = Set.of(
            // Creates an empty container; no provider call and no retrieval work.
            "ConversationController#createSession",
            // Both cancel an in-flight run rather than starting one.
            "AgentRunController#cancel",
            "AgentTaskController#cancel",
            // POST is only a query shape here: these read rows, nothing more.
            "KnowledgeBaseController#listKbs",
            "KnowledgeBaseController#stats",
            "PreviewController#getSegmentPreview",
            "PreviewController#refreshSegmentPreview",
            // KB creation writes a row and touches no provider.
            "KnowledgeBaseController#create"
    );

    @Test
    void everyVisitorWritableEndpointMustEitherBillOrBeJustified() {
        List<String> violations = new ArrayList<>();
        int checked = 0;

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        for (var candidate : scanner.findCandidateComponents("com.anchr.core")) {
            Class<?> controller = load(candidate.getBeanClassName());
            if (!isProductionClass(controller)) {
                continue;
            }
            for (Method method : ReflectionUtils.getUniqueDeclaredMethods(controller)) {
                if (!isWrite(method) || !isVisitorCallable(controller, method)) {
                    continue;
                }
                checked++;
                String label = controller.getSimpleName() + "#" + method.getName();
                boolean billed = AnnotatedElementUtils
                        .findMergedAnnotation(method, DemoQuota.class) != null;
                if (!billed && !FREE_ALLOWED.contains(label)) {
                    violations.add(label + " is visitor-writable but not billed");
                }
            }
        }

        assertThat(checked).as("there must be write endpoints to check").isGreaterThan(0);
        assertThat(violations)
                .as("Paid work reachable by a demo token must be metered, or listed in FREE_ALLOWED"
                        + " with a reason")
                .isEmpty();
    }

    private boolean isWrite(Method method) {
        return AnnotatedElementUtils.findMergedAnnotation(method, PostMapping.class) != null
                || AnnotatedElementUtils.findMergedAnnotation(method, PutMapping.class) != null;
    }

    private boolean isVisitorCallable(Class<?> controller, Method method) {
        RequireAuth requireAuth = AnnotatedElementUtils
                .findMergedAnnotation(method, RequireAuth.class);
        if (requireAuth == null) {
            requireAuth = AnnotatedElementUtils.findMergedAnnotation(controller, RequireAuth.class);
        }
        if (requireAuth == null) {
            return false;
        }
        return Arrays.asList(requireAuth.roles()).contains("USER")
                || Arrays.asList(requireAuth.roles()).contains("GUEST");
    }

    private boolean isProductionClass(Class<?> type) {
        var codeSource = type.getProtectionDomain().getCodeSource();
        return codeSource == null
                || !codeSource.getLocation().toExternalForm().contains("/test-classes/");
    }

    private Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
