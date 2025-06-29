---
sidebar_position: 1
title: Rutas y Controladores
description: |
  Aprende a manejar rutas y controladores en Ligero Framework.
---

# Rutas y Controladores

Esta guía te mostrará cómo trabajar con rutas y controladores en Ligero Framework.

## Tabla de Contenidos

- [Rutas Básicas](#rutas-básicas)
- [Parámetros de Ruta](#parámetros-de-ruta)
- [Métodos HTTP](#métodos-http)
- [Organización con Controladores](#organización-con-controladores)
- [Middleware de Ruta](#middleware-de-ruta)
- [Rutas Anidadas](#rutas-anidadas)
- [Manejo de Errores por Ruta](#manejo-de-errores-por-ruta)

## Rutas Básicas

Las rutas en Ligero se definen usando métodos que corresponden a los verbos HTTP:

```java
app.get("/", (req, res) -> {
    res.send("¡Hola, Mundo!");
});
```

## Parámetros de Ruta

Puedes capturar valores de la URL usando parámetros de ruta:

```java
// Ruta con parámetro obligatorio
app.get("/usuarios/{id}", (req, res) -> {
    String id = req.getPathParams().get("id");
    // Buscar usuario por ID
    res.json({"id": id, "nombre": "Usuario " + id});
});

// Parámetro opcional
app.get("/articulos/{id?}", (req, res) -> {
    String id = req.getPathParams().get("id");
    if (id != null) {
        // Mostrar artículo específico
        res.json({"id": id, "titulo": "Artículo " + id});
    } else {
        // Listar todos los artículos
        res.json([{"id": 1, "titulo": "Artículo 1"}]);
    }
});
```

## Métodos HTTP

Ligero soporta todos los métodos HTTP estándar:

```java
// GET - Obtener recursos
app.get("/recursos", (req, res) => {
    // Obtener lista de recursos
});

// POST - Crear un nuevo recurso
app.post("/recursos", (req, res) => {
    // Crear nuevo recurso
    String cuerpo = req.getBody();
    // Procesar y guardar
    res.status(201).json({"mensaje": "Recurso creado"});
});

// PUT - Actualizar un recurso existente
app.put("/recursos/{id}", (req, res) => {
    String id = req.getPathParams().get("id");
    // Actualizar recurso con ID
    res.json({"mensaje": "Recurso actualizado"});
});

// DELETE - Eliminar un recurso
app.delete("/recursos/{id}", (req, res) => {
    String id = req.getPathParams().get("id");
    // Eliminar recurso con ID
    res.status(204).end();
});

// PATCH - Actualización parcial
app.patch("/recursos/{id}", (req, res) => {
    String id = req.getPathParams().get("id");
    // Actualización parcial del recurso
    res.json({"mensaje": "Recurso actualizado parcialmente"});
});
```

## Organización con Controladores

Para mantener tu código organizado, puedes separar la lógica en controladores:

```java
// Controlador de Usuarios
class UsuarioController {
    public static void listar(HttpRequest req, HttpResponse res) {
        // Lógica para listar usuarios
        res.json([{"id": 1, "nombre": "Usuario 1"}]);
    }
    
    public static void obtener(HttpRequest req, HttpResponse res) {
        String id = req.getPathParams().get("id");
        // Lógica para obtener un usuario por ID
        res.json({"id": id, "nombre": "Usuario " + id});
    }
}

// En tu aplicación principal
app.get("/usuarios", UsuarioController::listar);
app.get("/usuarios/{id}", UsuarioController::obtener);
```

## Middleware de Ruta

Puedes agregar middleware específico para ciertas rutas:

```java
// Middleware de autenticación
BiConsumer<HttpRequest, HttpResponse> autenticar = (req, res, next) -> {
    String token = req.getHeader("Authorization");
    if (token == null || !token.equals("secreto")) {
        res.status(401).send("No autorizado");
        return;
    }
    next();
};

// Aplicar a rutas específicas
app.get("/ruta-protegida", autenticar, (req, res) -> {
    res.send("Contenido protegido");
});
```

## Rutas Anidadas

Puedes agrupar rutas relacionadas:

```java
// Grupo de rutas para la API v1
app.group("/api/v1", api -> {
    // GET /api/v1/usuarios
    api.get("/usuarios", (req, res) -> {
        // Listar usuarios v1
    });
    
    // GET /api/v1/productos
    api.get("/productos", (req, res) -> {
        // Listar productos v1
    });
});
```

## Manejo de Errores por Ruta

Puedes manejar errores específicos para ciertas rutas:

```java
app.get("/recurso-riesgoso", (req, res) -> {
    try {
        // Código que podría fallar
        if (Math.random() > 0.5) {
            throw new RuntimeException("¡Algo salió mal!");
        }
        res.send("¡Todo bien!");
    } catch (Exception e) {
        res.status(500).send("Error en el recurso: " + e.getMessage());
    }
});
```

## Buenas Prácticas

1. **Mantén las rutas limpias**: Las rutas deben ser descriptivas y consistentes
2. **Usa verbos HTTP correctamente**: 
   - GET para leer recursos
   - POST para crear
   - PUT para actualizaciones completas
   - PATCH para actualizaciones parciales
   - DELETE para eliminar
3. **Separa la lógica**: Usa controladores para separar la lógica de negocio
4. **Valida la entrada**: Siempre valida los datos de entrada
5. **Usa middleware**: Para tareas comunes como autenticación y registro

## Ejemplo Completo

```java
import com.ligero.Ligero;
import java.util.HashMap;
import java.util.Map;

public class TiendaAPI {
    public static void main(String[] args) {
        Ligero app = Ligero.create(3000);
        
        // Middleware de registro
        app.use((req, res, next) -> {
            System.out.println("[" + req.getMethod() + "] " + req.getUri());
            next();
        });
        
        // Rutas de productos
        app.group("/api/productos", productos -> {
            // GET /api/productos
            productos.get("", (req, res) -> {
                // Simular base de datos
                Map<String, Object>[] productos = new Map[]{
                    crearProducto(1, "Laptop", 999.99),
                    crearProducto(2, "Teléfono", 499.99)
                };
                res.json(productos);
            });
            
            // POST /api/productos
            productos.post("", (req, res) -> {
                // En una aplicación real, guardaríamos en la base de datos
                Map<String, Object> producto = new HashMap<>();
                producto.put("id", 3);
                producto.put("nombre", "Nuevo Producto");
                producto.put("precio", 199.99);
                
                res.status(201).json(producto);
            });
            
            // GET /api/productos/{id}
            productos.get("/{id}", (req, res) -> {
                String id = req.getPathParams().get("id");
                // Simular búsqueda
                if ("1".equals(id)) {
                    res.json(crearProducto(1, "Laptop", 999.99));
                } else {
                    res.status(404).json({"error": "Producto no encontrado"});
                }
            });
        });
        
        // Manejo de errores global
        app.error((req, res, error) -> {
            res.status(500).json({
                "error": "Error interno del servidor",
                "mensaje": error.getMessage()
            });
        });
        
        // Iniciar servidor
        app.start();
        System.out.println("Servidor iniciado en http://localhost:3000");
    }
    
    private static Map<String, Object> crearProducto(int id, String nombre, double precio) {
        Map<String, Object> producto = new HashMap<>();
        producto.put("id", id);
        producto.put("nombre", nombre);
        producto.put("precio", precio);
        return producto;
    }
}
```

## Conclusión

Las rutas y controladores son la columna vertebral de cualquier aplicación web. Con Ligero Framework, puedes organizar tu aplicación de manera limpia y mantenible. Recuerda seguir las mejores prácticas y mantener tu código organizado para facilitar el mantenimiento a largo plazo.
