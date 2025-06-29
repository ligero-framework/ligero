---
sidebar_position: 1
title: Introduction
description: |
  Welcome to the Ligero Framework documentation.
---

# Welcome to Ligero Framework

Ligero is a lightweight and minimalist web framework for building applications and APIs in Java. Designed to be simple, fast, and easy to use, without the complexities of traditional enterprise frameworks.

## Key Features

- **Minimal Configuration**: Get started quickly with minimal setup.
- **Optimized Performance**: Designed to be fast and efficient.
- **Expressive Routing**: Define routes clearly and concisely.
- **Flexible**: Supports different content types (JSON, HTML, plain text).
- **Extensible**: Easy to extend with your own functionality.

## Why Ligero?

- **Simple**: Intuitive and easy-to-learn API.
- **Lightweight**: No unnecessary dependencies.
- **Modern**: Based on Java 17+ with modern features.
- **Well-Documented**: Comprehensive documentation and examples.

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

## Your First Application

Create a new file `App.java` with the following content:

```java
import com.ligero.Ligero;

public class App {
    public static void main(String[] args) {
        Ligero app = Ligero.create(8080);
        
        app.get("/", (req, res) -> {
            res.send("¡Hola, Mundo!");
        });
        
        app.start();
    }
}
```

Ejecuta la aplicación y visita `http://localhost:8080` en tu navegador.

## Siguientes Pasos

- [Guía de Inicio Rápido](./getting-started/quick-start)
- [Conceptos Básicos](./getting-started/core-concepts)
- [Guías](./guides/)
- [Referencia de la API](./api/)

## Contribuir

¡Las contribuciones son bienvenidas! Por favor, lee nuestra [guía de contribución](https://github.com/tu-usuario/ligero-framework/blob/main/CONTRIBUTING.md) para más detalles.

## Licencia

Ligero Framework está licenciado bajo la [Licencia Apache 2.0](https://github.com/tu-usuario/ligero-framework/blob/main/LICENSE).

```bash
npm init docusaurus@latest my-website classic
```

You can type this command into Command Prompt, Powershell, Terminal, or any other integrated terminal of your code editor.

The command also installs all necessary dependencies you need to run Docusaurus.

## Start your site

Run the development server:

```bash
cd my-website
npm run start
```

The `cd` command changes the directory you're working with. In order to work with your newly created Docusaurus site, you'll need to navigate the terminal there.

The `npm run start` command builds your website locally and serves it through a development server, ready for you to view at http://localhost:3000/.

Open `docs/intro.md` (this page) and edit some lines: the site **reloads automatically** and displays your changes.
