---
sidebar_position: 1
title: Installation
description: |
  Learn how to install and set up Ligero Framework in your project.
---

# Installation

## Prerequisites

Before you begin, ensure you have the following installed:

- Java 17 or later
- Maven 3.6+ or Gradle 7.0+

## Maven

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.ligero</groupId>
    <artifactId>ligero-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Gradle

Add the following to your `build.gradle`:

```groovy
implementation 'com.ligero:ligero-core:1.0.0'
```

## Creating a New Project

### Using Maven Archetype

```bash
mvn archetype:generate \
    -DgroupId=com.example \
    -DartifactId=my-ligero-app \
    -DarchetypeArtifactId=maven-archetype-quickstart \
    -DinteractiveMode=false
```

Then add the Ligero dependency to the generated `pom.xml`.

### Using Spring Initializr

1. Visit [start.spring.io](https://start.spring.io/)
2. Add the "Spring Web" dependency
3. Generate and download the project
4. Add the Ligero dependency to your `pom.xml` or `build.gradle`

## Verifying the Installation

Create a simple application to verify the installation:

```java
import com.ligero.Ligero;

public class App {
    public static void main(String[] args) {
        Ligero app = Ligero.create(8080);
        
        app.get("/", (req, res) -> {
            res.send("Ligero is working! 🚀");
        });
        
        app.start();
    }
}
```

Run the application and visit `http://localhost:8080` in your browser. You should see the message "Ligero is working! 🚀".

## Next Steps

- [Quick Start](./quick-start) - Build your first Ligero application
- [Core Concepts](./core-concepts) - Learn the fundamentals of Ligero
- [API Reference](../api/app) - Explore the API documentation
