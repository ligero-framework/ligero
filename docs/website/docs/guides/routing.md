---
sidebar_position: 1
title: Routing
description: |
  Learn how to define routes and handle HTTP requests in Ligero.
---

# Routing in Ligero

Ligero's routing system allows you to define how your application responds to client requests. This guide covers everything you need to know about routing in Ligero.

## Basic Routing

The most basic Ligero routes accept a URI and a callback function:

```java
app.get("/", (req, res) -> {
    res.send("Hello, World!");
});
```

## Route Parameters

### Required Parameters

```java
app.get("/users/{id}", (req, res) -> {
    String userId = req.getPathParams().get("id");
    res.json(Map.of("id", userId, "name", "John Doe"));
});
```

### Optional Parameters

```java
app.get("/posts/{id?}", (req, res) -> {
    String postId = req.getPathParams().get("id");
    if (postId != null) {
        // Return specific post
        res.json(Map.of("id", postId, "title", "Sample Post"));
    } else {
        // Return all posts
        res.json(List.of(
            Map.of("id", 1, "title", "First Post"),
            Map.of("id", 2, "title", "Second Post")
        ));
    }
});
```

## HTTP Methods

Ligero supports all standard HTTP methods:

```java
// GET: Retrieve a resource
app.get("/resource", (req, res) -> {
    res.json(Map.of("message", "GET request"));
});

// POST: Create a new resource
app.post("/resource", (req, res) -> {
    String body = req.getBody();
    // Process the request body
    res.status(201).json(Map.of("status", "created"));
});

// PUT: Update an existing resource
app.put("/resource/{id}", (req, res) -> {
    String id = req.getPathParams().get("id");
    // Update the resource
    res.json(Map.of("id", id, "status", "updated"));
});

// DELETE: Delete a resource
app.delete("/resource/{id}", (req, res) -> {
    String id = req.getPathParams().get("id");
    // Delete the resource
    res.status(204).end();
});

// PATCH: Partially update a resource
app.patch("/resource/{id}", (req, res) -> {
    String id = req.getPathParams().get("id");
    // Partially update the resource
    res.json(Map.of("id", id, "status", "partially updated"));
});

// OPTIONS: Get supported HTTP methods
app.options("/resource", (req, res) -> {
    res.header("Allow", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
    res.end();
});
```

## Route Groups

Group related routes together for better organization:

```java
app.group("/api/v1", api -> {
    // GET /api/v1/users
    api.get("/users", (req, res) -> {
        // Return list of users
        res.json(List.of(
            Map.of("id", 1, "name", "John"),
            Map.of("id", 2, "name", "Jane")
        ));
    });
    
    // POST /api/v1/users
    api.post("/users", (req, res) -> {
        // Create a new user
        res.status(201).json(Map.of("status", "user created"));
    });
    
    // Nested groups
    api.group("/posts", posts -> {
        // GET /api/v1/posts
        posts.get("", (req, res) -> {
            // Return list of posts
            res.json(List.of(
                Map.of("id", 1, "title", "First Post"),
                Map.of("id", 2, "title", "Second Post")
            ));
        });
        
        // GET /api/v1/posts/{id}
        posts.get("/{id}", (req, res) -> {
            String postId = req.getPathParams().get("id");
            // Return specific post
            res.json(Map.of("id", postId, "title", "Sample Post"));
        });
    });
});
```

## Middleware

Middleware functions can be used to perform operations on requests and responses:

```java
// Application-level middleware
app.use((req, res, next) -> {
    System.out.printf("[%s] %s %s%n", 
        new java.util.Date(),
        req.getMethod(),
        req.getUri()
    );
    next();
});

// Route-specific middleware
app.get("/protected", (req, res, next) -> {
    String token = req.getHeader("Authorization");
    if (token == null || !token.equals("secret-token")) {
        res.status(401).json(Map.of("error", "Unauthorized"));
        return;
    }
    next();
}, (req, res) -> {
    res.json(Map.of("message", "This is a protected route"));
});
```

## Error Handling

Handle errors in your routes:

```java
// 404 Not Found
app.use((req, res) -> {
    res.status(404).json(Map.of(
        "error", "Not Found",
        "message", "The requested resource was not found"
    ));
});

// Global error handler
app.error((req, res, error) -> {
    System.err.println("Error:");
    error.printStackTrace();
    
    res.status(500).json(Map.of(
        "error", "Internal Server Error",
        "message", error.getMessage()
    ));
});
```

## Best Practices

1. **Organize routes by feature**
   ```java
   // In UserRoutes.java
   public class UserRoutes {
       public static void setup(Ligero app) {
           app.get("/users", UserController::list);
           app.post("/users", UserController::create);
           app.get("/users/{id}", UserController::get);
           app.put("/users/{id}", UserController::update);
           app.delete("/users/{id}", UserController::delete);
       }
   }
   ```

2. **Use constants for paths**
   ```java
   public class Routes {
       public static final String API = "/api";
       public static final String USERS = API + "/users";
       public static final String USER_BY_ID = USERS + "/{id}";
   }
   ```

3. **Validate input**
   ```java
   app.post("/users", (req, res) -> {
       User user = gson.fromJson(req.getBody(), User.class);
       if (user.getName() == null || user.getName().trim().isEmpty()) {
           res.status(400).json(Map.of("error", "Name is required"));
           return;
       }
       // Process valid user
   });
   ```

4. **Use proper HTTP status codes**
   - 200 OK - Successful GET requests
   - 201 Created - Resource created successfully
   - 204 No Content - Successful DELETE requests
   - 400 Bad Request - Invalid request data
   - 401 Unauthorized - Authentication required
   - 403 Forbidden - Insufficient permissions
   - 404 Not Found - Resource doesn't exist
   - 500 Internal Server Error - Server error

## Advanced Topics

### File Uploads

```java
app.post("/upload", (req, res) -> {
    Map<String, String> file = req.getFile("file");
    if (file != null) {
        // Process the uploaded file
        res.json(Map.of(
            "filename", file.get("filename"),
            "size", file.get("size")
        ));
    } else {
        res.status(400).json(Map.of("error", "No file uploaded"));
    }
});
```

### WebSocket Support

```java
app.ws("/chat", (ws) -> {
    ws.onConnect(session -> {
        System.out.println("Client connected: " + session.getId());
    });
    
    ws.onMessage((session, message) -> {
        // Broadcast message to all connected clients
        ws.broadcast("User " + session.getId() + ": " + message);
    });
    
    ws.onClose((session, statusCode, reason) -> {
        System.out.println("Client disconnected: " + session.getId());
    });
});
```

## Conclusion

Ligero's routing system is designed to be simple yet powerful, allowing you to build robust web applications and APIs. By following the patterns and best practices outlined in this guide, you can create maintainable and scalable applications with ease.
