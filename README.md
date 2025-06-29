<div align="center">
  <img src="docs/website/static/img/Ligero.svg" alt="Ligero Logo" width="200">
  
  # Ligero Framework
  
  [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0) [![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html) [![Documentation](https://img.shields.io/badge/Documentation-Online-brightgreen)](https://your-docs-url.com) [![Build Status](https://img.shields.io/github/actions/workflow/status/your-org/ligero/build.yml?branch=main)](https://github.com/your-org/ligero/actions)
</div>

## Overview

Ligero is a lightweight and minimalist web framework for building applications and APIs in Java. Designed to be simple, fast, and easy to use, without the complexities of traditional enterprise frameworks.

## Documentation

Comprehensive documentation is available at [https://your-docs-url.com](https://your-docs-url.com). The documentation includes:

- Getting Started guide
- API Reference
- Tutorials and Examples
- Best Practices
- Migration Guides

## Local Development

### Prerequisites

- Java 17 or higher
- Maven 3.6+ or Gradle 7.0+
- Node.js 16+ (for documentation website)

### Building the Project

```bash
# Clone the repository
git clone https://github.com/your-org/ligero.git
cd ligero

# Build the project
mvn clean install
```

### Documentation Website

The documentation website is built using [Docusaurus](https://docusaurus.io/). To run it locally:

```bash
# Navigate to the docs website directory
cd docs/website

# Install dependencies
npm install

# Start the development server
npm start
```

The website will be available at `http://localhost:3000`.

## Contributing

We welcome contributions! Please read our [Contributing Guide](CONTRIBUTING.md) to get started.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Features

- 🚀 Minimal configuration
- ⚡️ Optimized performance
- 🛣️ Simple and expressive routing
- 🔄 Route parameter support
- 📦 HTTP request/response handling
- 🎨 Support for different content types (JSON, HTML, plain text)
- 🔌 Easy to extend

## Requirements

- Java 17 or higher
- Maven 3.6 or higher

## Installation

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.ligero</groupId>
    <artifactId>ligero-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick Start

Create a new file `App.java` with the following content:

```java
import com.ligero.Ligero;

public class App {
    public static void main(String[] args) {
        // Create a new Ligero app on port 8080
        Ligero app = Ligero.create(8080);
        
        // Define a GET route for the root path
        app.get("/", (req, res) -> {
            res.send("Hello, World!");
        });
        
        // Start the server
        app.start();
    }
}
```

Run the application and visit `http://localhost:8080` in your browser.

## Complete Example

```java
import com.ligero.Ligero;
import java.util.Map;

public class ExampleApp {
    public static void main(String[] args) {
        // Create app with context path /api
        Ligero app = Ligero.create(8080, "/api");

        // Home page
        app.get("/", (req, res) -> {
            String html = "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "    <title>Ligero Framework</title>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <h1>Welcome to Ligero!</h1>\n" +
                "</body>\n" +
                "</html>";
            res.contentType("text/html; charset=utf-8").send(html);
        });

        // Route with parameter
        app.get("/greet/{name}", (req, res) -> {
            String name = req.getPathParams().get("name");
            res.json(Map.of(
                "message", "Hello, " + name + "!",
                "timestamp", System.currentTimeMillis()
            ));
        });

        // POST route
        app.post("/data", (req, res) -> {
            // Process POST data
            res.json(Map.of(
                "status", "received",
                "data", req.getBody(),
                "timestamp", System.currentTimeMillis()
            ));
        });

        // Start the server
        app.start();
        System.out.println("Server started at http://localhost:8080/api");
    }
}
```

## Documentation

For complete documentation, please visit our [documentation site](https://your-docs-site.com).

## Contributing

Contributions are welcome! Please read our [contribution guidelines](CONTRIBUTING.md) for details.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- All contributors who helped improve this project
- The open source community for inspiration and tools used.
