---
sidebar_position: 3
title: Core Concepts
description: |
  Learn the fundamental concepts of Ligero Framework.
---

# Core Concepts

This guide covers the fundamental concepts you need to understand to work with Ligero Framework.

## Ligero Application Structure

A typical Ligero application follows this structure:

```
mi-aplicacion/
├── pom.xml
└── src/
    └── main/
        └── java/
            └── com/
                └── ejemplo/
                    └── App.java
```

## La Clase Principal

La clase principal de tu aplicación debe contener el método `main` que inicia el servidor:

```java
import com.ligero.Ligero;

public class App {
    public static void main(String[] args) {
        Ligero app = Ligero.create(8080);
        // Configuración de rutas aquí
        app.start();
    }
}
```

## Rutas Básicas

Las rutas en Ligero se definen usando métodos que corresponden a los verbos HTTP:

```java
app.get("/ruta", (req, res) -> {
    // Manejar solicitud GET a /ruta
    res.send("Respuesta GET");
});

app.post("/ruta", (req, res) -> {
    // Manejar solicitud POST a /ruta
    res.json({"mensaje": "Datos recibidos"});
});

app.put("/ruta", (req, res) -> {
    // Manejar solicitud PUT a /ruta
    res.send("Recurso actualizado");
});

app.delete("/ruta", (req, res) -> {
    // Manejar solicitud DELETE a /ruta
    res.send("Recurso eliminado");
});
```

## Parámetros de Ruta

Puedes definir parámetros en las rutas usando la sintaxis `{parametro}`:

```java
app.get("/saludo/{nombre}", (req, res) -> {
    String nombre = req.getPathParams().get("nombre");
    res.send("¡Hola, " + nombre + "!");
});
```

## Manejo del Cuerpo de la Solicitud

Para acceder al cuerpo de una solicitud POST o PUT:

```java
app.post("/datos", (req, res) -> {
    String cuerpo = req.getBody();
    // Procesar el cuerpo (por ejemplo, JSON)
    res.json({"recibido": true, "datos": cuerpo});
});
```

## Configuración del Servidor

Puedes configurar el servidor con diferentes opciones:

```java
Ligero app = Ligero.create(8080, "/api"); // Puerto y contexto
```

## Tipos de Respuesta

Ligero soporta diferentes tipos de respuestas:

```java
// Texto plano
app.get("/texto", (req, res) -> {
    res.send("Hola en texto plano");
});

// JSON
app.get("/json", (req, res) -> {
    res.json({"mensaje": "Hola en JSON"});
});

// HTML
app.get("/html", (req, res) -> {
    res.contentType("text/html")
       .send("<h1>Hola en HTML</h1>");
});
```

## Middleware

Puedes agregar middleware para procesar todas las solicitudes:

```java
app.use((req, res, next) -> {
    // Este código se ejecutará para todas las solicitudes
    System.out.println("Solicitud recibida: " + req.getUri());
    next(); // Continuar con el siguiente middleware o manejador de ruta
});
```

## Manejo de Errores

Puedes manejar errores globalmente:

```java
app.error((req, res, error) -> {
    res.status(500).send("¡Algo salió mal! " + error.getMessage());
});
```

## Variables de Entorno

Para acceder a variables de entorno:

```java
String puerto = System.getenv("PUERTO");
if (puerto == null) {
    puerto = "8080"; // Valor por defecto
}
Ligero app = Ligero.create(Integer.parseInt(puerto));
```

## Buenas Prácticas

1. **Estructura de Proyecto**: Organiza tu código en paquetes lógicos
2. **Manejo de Errores**: Implementa un manejo de errores robusto
3. **Variables de Entorno**: Usa variables de entorno para configuración sensible
4. **Logging**: Implementa registro de eventos importantes
5. **Pruebas**: Escribe pruebas unitarias e integrales

## Rendimiento

- Ligero está diseñado para ser liviano y rápido
- Usa un modelo de hilos por solicitud
- Considera usar un proxy inverso como Nginx para producción

## Seguridad

- Valida siempre la entrada del usuario
- Usa HTTPS en producción
- Implementa autenticación y autorización según sea necesario
- Mantén las dependencias actualizadas

## Siguientes Pasos

Ahora que conoces los conceptos básicos, puedes:

1. Explorar las [Guías](../guides/) para ejemplos más avanzados
2. Consultar la [Referencia de la API](../api/) para detalles sobre todas las características
3. Ver ejemplos de implementaciones del mundo real en el [repositorio de ejemplos](https://github.com/tu-usuario/ligero-ejemplos)
