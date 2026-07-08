package com.ligero.validate;

import com.ligero.http.BadRequestException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Annotation-based request validation via Jakarta Bean Validation (Hibernate
 * Validator). Annotate your request record with the standard constraints and
 * validate it in one line — invalid input becomes a {@code 400} with every
 * violation reported, matching Ligero's error style.
 *
 * <pre>{@code
 * record NewUser(@NotBlank String name,
 *                @Email String email,
 *                @Min(18) int age) {}
 *
 * app.post("/users", ctx -> {
 *     NewUser user = Validate.orThrow(ctx.body(NewUser.class));   // 400 with all messages if invalid
 *     ctx.status(201).json(service.create(user));
 * });
 * }</pre>
 *
 * <p>This is opt-in: Bean Validation uses reflection, so it lives in its own
 * module. If you'd rather stay reflection-free, the core's
 * {@code ctx.bodyValidator(...)} gives you programmatic checks instead.</p>
 */
public final class Validate {

    private static final ValidatorFactory FACTORY =
        jakarta.validation.Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    private Validate() {
    }

    /** Returns {@code bean} if valid; otherwise throws a 400 listing every violation. */
    public static <T> T orThrow(T bean) {
        List<String> problems = errors(bean);
        if (!problems.isEmpty()) {
            throw new BadRequestException(String.join("; ", problems));
        }
        return bean;
    }

    /** All violation messages ({@code "field: message"}), sorted; empty when valid. */
    public static <T> List<String> errors(T bean) {
        Set<ConstraintViolation<T>> violations = VALIDATOR.validate(bean);
        return violations.stream()
            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.toList());
    }
}
