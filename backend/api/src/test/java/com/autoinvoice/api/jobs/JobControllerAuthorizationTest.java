package com.autoinvoice.api.jobs;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class JobControllerAuthorizationTest {
    @Test
    void detailUsesTheSameAuthorizationAsTheList() {
        Method list = method("list");
        Method get = method("get");

        assertThat(get.getAnnotation(PreAuthorize.class)).isNotNull();
        assertThat(get.getAnnotation(PreAuthorize.class).value())
                .isEqualTo(list.getAnnotation(PreAuthorize.class).value());
    }

    private Method method(String name) {
        return Arrays.stream(JobController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
