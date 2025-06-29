<div align="center">
  <img src="docs/website/static/img/Ligero.svg" alt="Ligero Logo" width="200">
  
  # Ligero Framework
  
  [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0) [![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://www.oracle.com/java/technologies/javase/jdk21-archive-downloads.html) [![Documentation](https://img.shields.io/badge/Documentation-Online-brightgreen)](https://ligero-framework.github.io) [![Maven Central](https://img.shields.io/maven-central/v/com.ligero/ligero-core.svg)](https://search.maven.org/search?q=g:com.ligero)

  <p><em>A lightweight Java web framework for modern applications</em></p>
</div>

## Overview

Ligero is a lightweight, minimalist web framework for building modern web applications and APIs in Java. Designed with simplicity and performance in mind, Ligero provides a clean, expressive API without the complexity of traditional enterprise frameworks.

```java
// Create a new Ligero app
Ligero app = Ligero.create(8080);

// Define a simple route
app.get("/hello", (req, res) -> {
    res.json(Map.of("message", "Hello, World!"));
});

// Start the server
app.start();
```

## Features

- ⚡️ **Lightweight & Fast**: Minimal overhead and optimized performance
- 🧩 **Simple API**: Intuitive, fluent API inspired by modern web frameworks
- 🛣️ **Expressive Routing**: Easy route definition with path parameters
- 🔄 **Content Negotiation**: Support for JSON, HTML, and plain text responses
- 🧪 **Testable**: Easy to test with minimal dependencies
- 🔌 **Extensible**: Designed to be extended with custom middleware and plugins
- 🔒 **Secure by Default**: Follows secure coding practices

## Installation

### Maven

```xml
<dependency>
    <groupId>com.ligero</groupId>
    <artifactId>ligero-core</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'com.ligero:ligero-core:0.1.0'
```

## Quick Start

### 1. Create a new Java project

Set up a new Java project with your favorite build tool.

### 2. Add Ligero as a dependency

Add Ligero to your project dependencies as shown above.

### 3. Create your application

```java
import com.ligero.Ligero;
import java.util.Map;

public class Application {
    public static void main(String[] args) {
        // Create a new Ligero app
        Ligero app = Ligero.create(8080);
        
        // Define routes
        app.get("/", (req, res) -> {
            res.send("Welcome to Ligero!");
        });
        
        app.get("/api/hello/{name}", (req, res) -> {
            String name = req.getPathParams().get("name");
            res.json(Map.of(
                "message", "Hello, " + name + "!",
                "timestamp", System.currentTimeMillis()
            ));
        });
        
        // Start the server
        app.start();
        System.out.println("Server started at http://localhost:8080");
    }
}
```

### 4. Run your application

Run your application and visit `http://localhost:8080` in your browser.

## Core Concepts

### Routes

Ligero uses a simple, expressive API for defining routes:

```java
// Basic routes
app.get("/users", (req, res) -> { /* handler */ });
app.post("/users", (req, res) -> { /* handler */ });
app.put("/users/{id}", (req, res) -> { /* handler */ });
app.delete("/users/{id}", (req, res) -> { /* handler */ });

// Path parameters
app.get("/users/{id}/posts/{postId}", (req, res) -> {
    String userId = req.getPathParams().get("id");
    String postId = req.getPathParams().get("postId");
    // ...
});

// Fallback for not found routes
app.fallback((req, res) -> {
    res.status(404).json(Map.of(
        "error", "Not found",
        "path", req.getUri()
    ));
});
```

### Requests & Responses

Ligero provides a simple API for handling HTTP requests and responses:

```java
// Request handling
app.post("/api/data", (req, res) -> {
    // Get request body as string
    String body = req.getBodyAsString();
    
    // Get query parameters
    String param = req.getQueryParam("param");
    
    // Get headers
    String userAgent = req.getHeader("User-Agent");
    
    // Response handling
    res.status(201)                         // Set status code
       .header("X-Custom-Header", "value")  // Set header
       .contentType("application/json")     // Set content type
       .json(Map.of("status", "created"));  // Send JSON response
});
```

## Advanced Usage

### Context Paths

You can set a context path for your application:

```java
// Create app with context path /api
Ligero app = Ligero.create(8080, "/api");

// Now all routes are under /api
app.get("/users", (req, res) -> { /* accessible at /api/users */ });
```

### Graceful Shutdown

Ligero supports graceful shutdown:

```java
// Create and configure app
Ligero app = Ligero.create(8080);
// ...

// Add shutdown hook
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    System.out.println("Shutting down server...");
    app.close();
    System.out.println("Server stopped.");
}));
```

## Examples

Check out the [examples directory](examples/src/main/java/com/ligero/examples) for more examples of using Ligero.

## Roadmap

- [ ] Middleware support
- [ ] WebSocket support
- [ ] Static file serving
- [ ] Template engine integration
- [ ] Form data parsing
- [ ] CORS support
- [ ] Authentication helpers
- [ ] OpenAPI/Swagger integration

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- Inspired by modern web frameworks like Express.js
- Built with love by the Ligero team
