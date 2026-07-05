---
sidebar_position: 1
---

# Installation

Ligero requires **Java 21+**.

:::note
Ligero is not yet on Maven Central. Until the first public release, build it locally:
`git clone https://github.com/ligero-framework/ligero && cd ligero && ./gradlew publishToMavenLocal`
and add `mavenLocal()` to your repositories.
:::

## Gradle

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'com.ligero:ligero-core:0.2.0-SNAPSHOT'       // the API
    runtimeOnly    'com.ligero:ligero-server-jdk:0.2.0-SNAPSHOT' // a server engine
    runtimeOnly    'com.ligero:ligero-json:0.2.0-SNAPSHOT'       // JSON support
    runtimeOnly    'org.slf4j:slf4j-simple:2.0.16'               // any SLF4J binding
}
```

## Maven

```xml
<dependency>
  <groupId>com.ligero</groupId>
  <artifactId>ligero-core</artifactId>
  <version>0.2.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>com.ligero</groupId>
  <artifactId>ligero-server-jdk</artifactId>
  <version>0.2.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>com.ligero</groupId>
  <artifactId>ligero-json</artifactId>
  <version>0.2.0-SNAPSHOT</version>
</dependency>
```

## Why three artifacts?

`ligero-core` only defines the API and SPIs. A **server engine**
(`ligero-server-jdk` or `ligero-server-jetty`) and a **JSON mapper**
(`ligero-json`) plug in at runtime via `ServiceLoader` — that's what keeps
the core at zero dependencies. See [Modules](../reference/modules) for the full list.

## Using the CLI

The fastest start is the [Ligero CLI](https://github.com/ligero-framework/ligero-cli):

```bash
ligero new my-api --package com.acme.api
cd my-api && gradle run
```
