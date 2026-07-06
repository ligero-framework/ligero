# Security Policy

## Supported Versions

Ligero is pre-1.0: only the latest published minor version receives security
fixes. From 1.0.0 onward the policy will be "latest minor plus the previous
one".

## Reporting a Vulnerability

Please **do not open a public issue** for security problems. Instead:

1. Use GitHub's private vulnerability reporting on this repository
   (*Security → Report a vulnerability*), or
2. Email the maintainer listed in `gradle.properties`.

You will receive an acknowledgement within 7 days. Please include a proof of
concept and the affected version/commit when possible.

## Scope notes

- `ligero-core` has no third-party dependencies except `slf4j-api`, which
  keeps the framework's supply-chain surface deliberately small.
- Dependency alerts are handled by Dependabot plus the `dependency-review`
  workflow on pull requests.
- Built-in protections: request body limits (413), path-traversal checks in
  static file serving, opaque 500 responses (no stack traces to clients),
  HMAC-signed session cookies, constant-time token comparisons.
