package com.ligero.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BodyValidatorTest {

    record User(String name, int age) {
    }

    @Test
    void returnsValueWhenAllChecksPass() {
        User user = new BodyValidator<>(new User("Ada", 36))
            .check(u -> u.name() != null, "name is required")
            .check(u -> u.age() >= 0, "age must be positive")
            .get();
        assertThat(user.name()).isEqualTo("Ada");
    }

    @Test
    void accumulatesAllFailures() {
        assertThatThrownBy(() -> new BodyValidator<>(new User(null, -1))
                .check(u -> u.name() != null, "name is required")
                .check(u -> u.age() >= 0, "age must be positive")
                .get())
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).getErrors())
                .containsExactly("name is required", "age must be positive"));
    }

    @Test
    void throwingPredicateCountsAsFailure() {
        assertThatThrownBy(() -> new BodyValidator<>(new User(null, 1))
                .check(u -> u.name().length() > 0, "name is required")
                .get())
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void validationExceptionMapsTo400() {
        ValidationException e = new ValidationException(java.util.List.of("boom"));
        assertThat(e.getStatus()).isEqualTo(400);
    }
}
