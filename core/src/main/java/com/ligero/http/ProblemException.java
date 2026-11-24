package com.ligero.http;

import java.util.Objects;

/**
 * An {@link HttpException} that carries a full {@link ProblemDetail}. The
 * framework answers it with that problem as {@code application/problem+json},
 * whether or not {@code app.problemDetails(true)} is on.
 *
 * <pre>{@code
 * throw new ProblemException(ProblemDetail.of(403)
 *     .type("https://example.com/probs/out-of-credit")
 *     .title("You do not have enough credit")
 *     .detail("Your balance is 30, but that costs 50")
 *     .with("balance", 30));
 * }</pre>
 */
public class ProblemException extends HttpException {

    private final ProblemDetail problem;

    public ProblemException(ProblemDetail problem) {
        super(Objects.requireNonNull(problem, "problem").status(),
            problem.detail() != null ? problem.detail() : problem.title());
        this.problem = problem;
    }

    public ProblemException(ProblemDetail problem, Throwable cause) {
        super(Objects.requireNonNull(problem, "problem").status(),
            problem.detail() != null ? problem.detail() : problem.title(), cause);
        this.problem = problem;
    }

    @Override
    public ProblemDetail toProblem() {
        return problem;
    }
}
