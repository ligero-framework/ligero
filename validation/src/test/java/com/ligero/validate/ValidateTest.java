package com.ligero.validate;

import com.ligero.http.BadRequestException;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidateTest {

    record NewUser(@NotBlank String name, @Email String email, @Min(18) int age) {
    }

    @Test
    void returnsBeanWhenValid() {
        NewUser user = new NewUser("Ada", "ada@example.com", 30);
        assertThat(Validate.orThrow(user)).isSameAs(user);
        assertThat(Validate.errors(user)).isEmpty();
    }

    @Test
    void reportsEveryViolationAsA400() {
        NewUser invalid = new NewUser("  ", "not-an-email", 15);
        assertThatThrownBy(() -> Validate.orThrow(invalid))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("name:")
            .hasMessageContaining("email:")
            .hasMessageContaining("age:");
    }

    @Test
    void errorsAreFieldPrefixedAndSorted() {
        var errors = Validate.errors(new NewUser("", "bad", 0));
        assertThat(errors).hasSize(3);
        assertThat(errors.get(0)).startsWith("age:");   // sorted: age < email < name
        assertThat(errors.get(2)).startsWith("name:");
    }
}
