package com.anchr.core.auth.interfaces.rest;

import com.anchr.core.auth.application.acl.AuthStorageAcl;
import com.anchr.core.auth.application.model.AuthStorageCredential;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AuthControllerHttpContractTest {

    @Test
    void stsPathAndJsonContractShouldRemainUnchanged() throws Exception {
        AuthStorageAcl storageAcl = mock(AuthStorageAcl.class);
        when(storageAcl.issueUploadCredential()).thenReturn(
                new AuthStorageCredential(
                        "https://oss", "bucket", "cn-test", "uploads/",
                        "temp-ak", "temp-sk", "token", "expiry"));
        AuthController controller = new AuthController(
                mock(StringRedisTemplate.class),
                storageAcl,
                new ObjectMapper());
        MockMvc mockMvc = standaloneSetup(controller).build();

        mockMvc.perform(get("/api/v1/auth/sts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.endpoint").value("https://oss"))
                .andExpect(jsonPath("$.data.bucket").value("bucket"))
                .andExpect(jsonPath("$.data.region").value("cn-test"))
                .andExpect(jsonPath("$.data.prefix").value("uploads/"))
                .andExpect(jsonPath("$.data.accessKeyId").value("temp-ak"))
                .andExpect(jsonPath("$.data.accessKeySecret").value("temp-sk"))
                .andExpect(jsonPath("$.data.securityToken").value("token"))
                .andExpect(jsonPath("$.data.expiration").value("expiry"));
    }
}
