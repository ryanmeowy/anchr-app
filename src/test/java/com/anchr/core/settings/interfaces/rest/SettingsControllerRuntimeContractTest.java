package com.anchr.core.settings.interfaces.rest;

import com.anchr.core.common.infrastructure.RequireAuth;
import com.anchr.core.settings.interfaces.rest.dto.RuntimeConfigUpdateRequestDTO;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsControllerRuntimeContractTest {

    @Test
    void keepsRuntimeConfigRoutesAndReadWritePermissionsExplicit() throws Exception {
        RequestMapping controllerMapping =
                SettingsController.class.getAnnotation(RequestMapping.class);
        assertThat(controllerMapping.value()).containsExactly("/api/v1/settings");

        Method get = SettingsController.class.getMethod("getRuntimeConfig");
        assertThat(get.getAnnotation(GetMapping.class).value()).containsExactly("/runtime");
        assertThat(Set.of(get.getAnnotation(RequireAuth.class).roles()))
                .containsExactlyInAnyOrder("ADMIN", "USER", "GUEST");

        Method put = SettingsController.class.getMethod(
                "updateRuntimeConfig", RuntimeConfigUpdateRequestDTO.class);
        assertThat(put.getAnnotation(PutMapping.class).value()).containsExactly("/runtime");
        assertThat(put.getAnnotation(RequireAuth.class).roles())
                .containsExactly("ADMIN");
    }
}
