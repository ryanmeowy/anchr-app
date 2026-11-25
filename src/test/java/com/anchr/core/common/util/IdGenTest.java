package com.anchr.core.common.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdGenTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private IdGen idGen;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        idGen = new IdGen(redisTemplate);
        ReflectionTestUtils.setField(idGen, "activeProfile", "dev");
    }

    @Test
    void initCreatesStableCounterWhenNoCounterExists() {
        when(valueOperations.get("id:gen")).thenReturn(null);
        when(valueOperations.get("id:gen:dev")).thenReturn(null);

        idGen.init();

        verify(valueOperations).setIfAbsent("id:gen", "1000000000");
    }

    @Test
    void initMigratesLegacyProfileScopedCounter() {
        when(valueOperations.get("id:gen")).thenReturn(null);
        when(valueOperations.get("id:gen:dev")).thenReturn("1500000000");

        idGen.init();

        verify(valueOperations).setIfAbsent("id:gen", "1500000000");
    }

    @Test
    void initDoesNotOverwriteAnExistingStableCounter() {
        when(valueOperations.get("id:gen")).thenReturn("2000000000");

        idGen.init();

        verify(valueOperations, never()).setIfAbsent(anyString(), anyString());
        verify(valueOperations, never()).get("id:gen:dev");
    }

    @Test
    void initRejectsAnInvalidStableCounter() {
        when(valueOperations.get("id:gen")).thenReturn("not-a-number");

        assertThrows(IllegalStateException.class, idGen::init);

        verify(valueOperations, never()).setIfAbsent(anyString(), anyString());
    }

    @Test
    void nextIdAllocatesFromTheStableCounter() {
        when(valueOperations.get("id:gen")).thenReturn("1000000000");
        when(valueOperations.increment("id:gen", 1000)).thenReturn(1_000_001_000L);
        idGen.init();

        long id = idGen.nextId();

        assertTrue(id > 1_000_000_000L);
        assertTrue(id <= 1_000_000_100L);
    }
}
