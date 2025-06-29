---
sidebar_position: 2
title: Quick Start
description: |
  Get started quickly with Ligero Framework.
---

# Quick Start

This guide will help you create your first Ligero Framework application in minutes.

## Prerequisites

- Java 17 or higher
- Maven 3.6 or higher
- A code editor (VS Code, IntelliJ IDEA, etc.)

## Step 1: Create a New Maven Project

Create a new directory for your project and inside it, create a `pom.xml` file with the following content:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.ejemplo</groupId>
    <artifactId>mi-aplicacion-ligero</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>com.ligero</groupId>
            <artifactId>ligero-core</artifactId>
            <version>1.0.0</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.8.1</version>
            </plugin>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.0.0</version>
                <configuration>
                    <mainClass>com.ejemplo.App</mainClass>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

## Paso 2: Crear la estructura de directorios

Crea la siguiente estructura de directorios para tu aplicación:

```
src/
└── main/
    └── java/
        └── com/
            └── ejemplo/
                └── App.java
```

## Paso 3: Crear la aplicación de ejemplo

Abre el archivo `App.java` y agrega el siguiente código:

```java
package com.ejemplo;

import com.ligero.Ligero;

public class App {
    public static void main(String[] args) {
        // Crear una instancia de la aplicación en el puerto 8080
        Ligero app = Ligero.create(8080);
        
        // Definir una ruta GET para la raíz
        app.get("/", (req, res) -> {
            res.send("¡Hola, Mundo!");
        });
        
        // Iniciar el servidor
        app.start();
        
        System.out.println("Servidor iniciado en http://localhost:8080");
    }
}
```

## Paso 4: Ejecutar la aplicación

Puedes ejecutar la aplicación de dos maneras:

### Opción 1: Usando Maven

```bash
mvn compile exec:java
```

### Opción 2: Compilando y ejecutando manualmente

1. Compila el proyecto:
   ```bash
   mvn compile
   ```

2. Ejecuta la aplicación:
   ```bash
   mvn exec:java
   ```

## Paso 5: Probar la aplicación

Abre tu navegador web y visita:

```
http://localhost:8080
```

Deberías ver el mensaje: "¡Hola, Mundo!"

## Siguientes Pasos

¡Felicidades! Has creado tu primera aplicación con Ligero Framework. Ahora puedes:

1. Aprender sobre los [Conceptos Básicos](../getting-started/core-concepts)
2. Explorar las [Guías](../guides/)
3. Consultar la [Referencia de la API](../api/)

## Solución de Problemas

Si encuentras algún problema, asegúrate de que:

1. Tienes Java 17 o superior instalado
2. La variable de entorno `JAVA_HOME` está configurada correctamente
3. El puerto 8080 no está siendo utilizado por otra aplicación
4. Todas las dependencias se han descargado correctamente (revisa la salida de Maven)

## Obtener Ayuda

Si necesitas ayuda, por favor:

1. Revisa la [documentación](../)
2. Abre un [issue](https://github.com/tu-usuario/ligero-framework/issues) en GitHub
3. Únete a nuestro [canal de Discord/Slack](https://enlace-al-canal) (si está disponible)
