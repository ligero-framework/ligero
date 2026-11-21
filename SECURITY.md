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
  static file serving (including symlink escapes) and in the request path,
  opaque 500 responses (no stack traces to clients), HMAC-signed session
  cookies with idle expiry, constant-time token comparisons, validated cookie
  names/values, same-origin checks on WebSocket handshakes, `Origin`
  validation on the MCP endpoint, bounded in-memory rate-limit and session
  stores.
- CORS never combines credentials with a wildcard origin: an explicit origin
  allow-list is required.
- `ligero-devtools` is a development tool. It answers only to loopback
  clients by default; do not ship it enabled in production.

## Deployment checklist

- Serve over TLS and enable HSTS:
  `SecurityHeadersMiddleware.builder().hsts(Duration.ofDays(365)).build()`.
- Mark session cookies `Secure` (`SessionMiddleware.of(secret).secureCookie(true)`)
  and call `SessionMiddleware.regenerate(ctx)` after a successful login.
- Behind a reverse proxy, install `TrustedProxyMiddleware` with your proxies'
  addresses so rate limits and logs see the real client, and raise the Pluma
  engine's per-address connection limit.
- Serving TLS directly: use `ligero-server-pluma` with `LIGERO_TLS_CERT` /
  `LIGERO_TLS_KEY`; only TLS 1.3/1.2 with forward-secret AEAD suites are
  offered.
- Do not enable response compression on routes whose body mixes secrets with
  attacker-influenced text (BREACH).
- Keep secrets (JWT keys, session secrets, database passwords) in the
  environment, never in `ligero.yml` committed to source control.
- Use `UploadedFile.safeFilename()` — never the raw client filename — when
  storing uploads.
- When the application fetches URLs that users supply (webhooks, imports,
  avatars), build that `RestClient` with `AddressFilter.publicOnly()` so it
  cannot be pointed at internal services or the cloud metadata endpoint, and
  restrict outbound traffic at the network level too (the filter cannot stop
  DNS rebinding on its own).
- Public GraphQL APIs: keep the depth and complexity limits, and consider
  `introspection(false)`. Do not enable gRPC `reflection()` on public ports.
- Message consumers (Kafka, JMS, AMQP) receive data from whoever can write to
  the topic or queue: validate payloads like request bodies. Ligero never uses
  Java serialization for messages.
