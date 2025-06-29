# Publishing Ligero Framework to Maven Central

This guide explains how to publish the Ligero Framework to Maven Central.

## Prerequisites

Before you can publish to Maven Central, you need:

1. **Sonatype OSSRH Account**: Register at [Sonatype JIRA](https://issues.sonatype.org/secure/Signup)
2. **GPG Key Pair**: Used to sign your artifacts

## Setup Steps

### 1. Create a Sonatype OSSRH Account

1. Sign up at [Sonatype JIRA](https://issues.sonatype.org/secure/Signup)
2. Create a New Project ticket requesting access to publish under `com.ligero` group ID
3. Prove ownership of the domain (if you're using a domain-based group ID)
4. Wait for approval (this may take a few days)

### 2. Generate GPG Key Pair

```bash
# Generate a new GPG key
gpg --gen-key

# List your keys to get the key ID
gpg --list-keys --keyid-format SHORT

# Export your public key to a file
gpg --export --armor your-key-id > public-key.asc

# Export your private key to a file (keep this secure!)
gpg --export-secret-keys --armor your-key-id > private-key.asc

# Upload your public key to a keyserver
gpg --keyserver keyserver.ubuntu.com --send-keys your-key-id
```

### 3. Configure Gradle Properties

Update your `~/.gradle/gradle.properties` file with your credentials:

```properties
# Sonatype credentials
mavenCentralUsername=your-sonatype-username
mavenCentralPassword=your-sonatype-password

# Signing information
signing.keyId=your-key-id-last-8-chars
signing.password=your-gpg-key-password
signing.secretKeyRingFile=/path/to/your/secring.gpg
```

## Publishing Process

### 1. Prepare for Release

Run the `prepareRelease` task to update the version from SNAPSHOT to release:

```bash
./gradlew prepareRelease
```

### 2. Build and Publish

Build and publish all artifacts to Maven Central:

```bash
./gradlew clean build publishToSonatype closeSonatypeStagingRepository
```

### 3. Release to Maven Central

After verifying the staged artifacts in the Sonatype repository manager:

```bash
./gradlew releaseSonatypeStagingRepository
```

### 4. Prepare for Next Development Cycle

Update the version to the next SNAPSHOT:

```bash
./gradlew prepareNextDevelopment
```

## Publishing a Test Version

For test versions, you can use the `-SNAPSHOT` suffix which will publish to the Sonatype snapshots repository:

```bash
# Ensure version in gradle.properties ends with -SNAPSHOT
./gradlew publishToSonatype
```

Snapshot versions are available immediately without the need for closing and releasing the repository.

## Versioning Strategy

Follow semantic versioning (MAJOR.MINOR.PATCH):

- **MAJOR**: Incompatible API changes
- **MINOR**: New functionality in a backward-compatible manner
- **PATCH**: Backward-compatible bug fixes

For a framework in development:
- Start with `0.1.0-SNAPSHOT` for initial development
- Release `0.1.0` for the first stable feature set
- Continue with `0.2.0-SNAPSHOT` for next development cycle
- When API is stable and production-ready, move to `1.0.0`

## Troubleshooting

- **Signature Verification Failed**: Ensure your GPG key is properly configured and uploaded to a public keyserver
- **Repository Validation Failed**: Check that all required POM elements are present (name, description, URL, licenses, developers, SCM)
- **Artifact Rejected**: Verify that all artifacts have proper Javadoc and source JARs

## Additional Resources

- [Sonatype OSSRH Guide](https://central.sonatype.org/publish/publish-guide/)
- [Gradle Signing Plugin Documentation](https://docs.gradle.org/current/userguide/signing_plugin.html)
- [Semantic Versioning](https://semver.org/)
