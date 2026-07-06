package com.ligero.middleware;

import com.ligero.http.Context;

import java.time.Duration;

/**
 * Adds conservative security headers to every response:
 * {@code X-Content-Type-Options}, {@code X-Frame-Options},
 * {@code Referrer-Policy}, and optionally HSTS and a Content-Security-Policy.
 */
public final class SecurityHeadersMiddleware implements Middleware {

    private final String frameOptions;
    private final String referrerPolicy;
    private final String contentSecurityPolicy;
    private final long hstsMaxAgeSeconds; // <= 0 disables HSTS

    private SecurityHeadersMiddleware(Builder builder) {
        this.frameOptions = builder.frameOptions;
        this.referrerPolicy = builder.referrerPolicy;
        this.contentSecurityPolicy = builder.contentSecurityPolicy;
        this.hstsMaxAgeSeconds = builder.hstsMaxAge == null ? 0 : builder.hstsMaxAge.toSeconds();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Sensible defaults without HSTS/CSP. */
    public static SecurityHeadersMiddleware defaults() {
        return builder().build();
    }

    @Override
    public void handle(Context ctx, Chain chain) throws Exception {
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.header("X-Frame-Options", frameOptions);
        ctx.header("Referrer-Policy", referrerPolicy);
        if (hstsMaxAgeSeconds > 0) {
            ctx.header("Strict-Transport-Security", "max-age=" + hstsMaxAgeSeconds + "; includeSubDomains");
        }
        if (contentSecurityPolicy != null) {
            ctx.header("Content-Security-Policy", contentSecurityPolicy);
        }
        chain.proceed();
    }

    public static final class Builder {
        private String frameOptions = "DENY";
        private String referrerPolicy = "no-referrer";
        private String contentSecurityPolicy;
        private Duration hstsMaxAge;

        public Builder frameOptions(String frameOptions) {
            this.frameOptions = frameOptions;
            return this;
        }

        public Builder referrerPolicy(String referrerPolicy) {
            this.referrerPolicy = referrerPolicy;
            return this;
        }

        public Builder contentSecurityPolicy(String csp) {
            this.contentSecurityPolicy = csp;
            return this;
        }

        public Builder hsts(Duration maxAge) {
            this.hstsMaxAge = maxAge;
            return this;
        }

        public SecurityHeadersMiddleware build() {
            return new SecurityHeadersMiddleware(this);
        }
    }
}
