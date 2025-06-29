---
sidebar_position: 2
title: Building a REST API
description: |
  Step-by-step guide to building a RESTful API with Ligero Framework
---

# Building a REST API with Ligero

This guide will walk you through creating a complete RESTful API using Ligero Framework. We'll build a simple Task Management API with CRUD operations.

## Prerequisites

- Java 17 or higher
- Maven 3.6 or higher
- Basic knowledge of Java and REST concepts

## Step 1: Set Up Your Project

1. Create a new Maven project:
   ```bash
   mvn archetype:generate -DgroupId=com.example -DartifactId=task-api -DarchetypeArtifactId=maven-archetype-quickstart -DinteractiveMode=false
   cd task-api
   ```

2. Update the `pom.xml` to include Ligero and other dependencies:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>task-api</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <ligero.version>1.0.0</ligero.version>
    </properties>

    <dependencies>
        <!-- Ligero Framework -->
        <dependency>
            <groupId>com.ligero</groupId>
            <artifactId>ligero-core</artifactId>
            <version>${ligero.version}</version>
        </dependency>
        
        <!-- JSON Processing -->
        <dependency>
            <groupId>com.google.code.gson</groupId>
            <artifactId>gson</artifactId>
            <version>2.8.9</version>
        </dependency>
        
        <!-- Logging -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-simple</artifactId>
            <version>1.7.32</version>
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
                    <mainClass>com.example.Main</mainClass>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

## Step 2: Create the Task Model

Create a new file `src/main/java/com/example/model/Task.java`:

```java
package com.example.model;

import java.time.LocalDateTime;

public class Task {
    private String id;
    private String title;
    private String description;
    private boolean completed;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
```

## Step 3: Create a Task Service

Create `src/main/java/com/example/service/TaskService.java`:

```java
package com.example.service;

import com.example.model.Task;
import java.util.*;
import java.time.LocalDateTime;

public class TaskService {
    private final Map<String, Task> tasks = new HashMap<>();
    
    public List<Task> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }
    
    public Optional<Task> getTaskById(String id) {
        return Optional.ofNullable(tasks.get(id));
    }
    
    public Task createTask(Task task) {
        String id = UUID.randomUUID().toString();
        task.setId(id);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        tasks.put(id, task);
        return task;
    }
    
    public Optional<Task> updateTask(String id, Task taskUpdates) {
        return getTaskById(id).map(existingTask -> {
            if (taskUpdates.getTitle() != null) {
                existingTask.setTitle(taskUpdates.getTitle());
            }
            if (taskUpdates.getDescription() != null) {
                existingTask.setDescription(taskUpdates.getDescription());
            }
            existingTask.setCompleted(taskUpdates.isCompleted());
            existingTask.setUpdatedAt(LocalDateTime.now());
            return existingTask;
        });
    }
    
    public boolean deleteTask(String id) {
        return tasks.remove(id) != null;
    }
}
```

## Step 4: Create the Main Application

Create `src/main/java/com/example/Main.java`:

```java
package com.example;

import com.example.model.Task;
import com.example.service.TaskService;
import com.google.gson.Gson;
import com.ligero.Ligero;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public class Main {
    private static final Gson gson = new Gson();
    private static final TaskService taskService = new TaskService();
    
    public static void main(String[] args) {
        // Initialize with some sample data
        initializeSampleData();
        
        // Create Ligero app
        Ligero app = Ligero.create(8080);
        
        // Enable CORS for all routes
        app.use((req, res, next) -> {
            res.header("Access-Control-Allow-Origin", "*");
            res.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            res.header("Access-Control-Allow-Headers", "Content-Type");
            next();
        });
        
        // Handle preflight requests
        app.options("/*", (req, res) -> {
            res.status(200).end();
        });
        
        // GET /api/tasks - Get all tasks
        app.get("/api/tasks", (req, res) -> {
            res.json(taskService.getAllTasks());
        });
        
        // GET /api/tasks/:id - Get a specific task
        app.get("/api/tasks/{id}", (req, res) -> {
            String id = req.getPathParams().get("id");
            taskService.getTaskById(id).ifPresentOrElse(
                task -> res.json(task),
                () -> res.status(404).json(Map.of("error", "Task not found"))
            );
        });
        
        // POST /api/tasks - Create a new task
        app.post("/api/tasks", (req, res) -> {
            try {
                Task newTask = gson.fromJson(req.getBody(), Task.class);
                Task createdTask = taskService.createTask(newTask);
                res.status(201).json(createdTask);
            } catch (Exception e) {
                res.status(400).json(Map.of("error", "Invalid task data"));
            }
        });
        
        // PUT /api/tasks/:id - Update a task
        app.put("/api/tasks/{id}", (req, res) -> {
            String id = req.getPathParams().get("id");
            try {
                Task taskUpdates = gson.fromJson(req.getBody(), Task.class);
                taskService.updateTask(id, taskUpdates).ifPresentOrElse(
                    updatedTask -> res.json(updatedTask),
                    () -> res.status(404).json(Map.of("error", "Task not found"))
                );
            } catch (Exception e) {
                res.status(400).json(Map.of("error", "Invalid task data"));
            }
        });
        
        // DELETE /api/tasks/:id - Delete a task
        app.delete("/api/tasks/{id}", (req, res) -> {
            String id = req.getPathParams().get("id");
            if (taskService.deleteTask(id)) {
                res.status(204).end();
            } else {
                res.status(404).json(Map.of("error", "Task not found"));
            }
        });
        
        // Start the server
        app.start();
        System.out.println("Task API is running on http://localhost:8080");
    }
    
    private static void initializeSampleData() {
        Task task1 = new Task();
        task1.setId(UUID.randomUUID().toString());
        task1.setTitle("Learn Ligero Framework");
        task1.setDescription("Build a REST API with Ligero");
        task1.setCompleted(false);
        task1.setCreatedAt(LocalDateTime.now());
        task1.setUpdatedAt(LocalDateTime.now());
        
        Task task2 = new Task();
        task2.setId(UUID.randomUUID().toString());
        task2.setTitle("Document the API");
        task2.setDescription("Write documentation for the Task API");
        task2.setCompleted(true);
        task2.setCreatedAt(LocalDateTime.now());
        task2.setUpdatedAt(LocalDateTime.now());
        
        taskService.createTask(task1);
        taskService.createTask(task2);
    }
}
```

## Step 5: Run the Application

1. Run the application using Maven:
   ```bash
   mvn compile exec:java
   ```

2. The API will be available at `http://localhost:8080`

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET    | /api/tasks | Get all tasks |
| GET    | /api/tasks/{id} | Get a specific task |
| POST   | /api/tasks | Create a new task |
| PUT    | /api/tasks/{id} | Update a task |
| DELETE | /api/tasks/{id} | Delete a task |

## Testing the API

You can test the API using `curl` or a tool like Postman.

### Get all tasks:
```bash
curl http://localhost:8080/api/tasks
```

### Get a specific task:
```bash
# Replace {id} with an actual task ID from the previous response
curl http://localhost:8080/api/tasks/{id}
```

### Create a new task:
```bash
curl -X POST -H "Content-Type: application/json" -d '{
  "title": "New Task",
  "description": "This is a new task",
  "completed": false
}' http://localhost:8080/api/tasks
```

### Update a task:
```bash
# Replace {id} with an actual task ID
curl -X PUT -H "Content-Type: application/json" -d '{
  "title": "Updated Task",
  "completed": true
}' http://localhost:8080/api/tasks/{id}
```

### Delete a task:
```bash
# Replace {id} with an actual task ID
curl -X DELETE http://localhost:8080/api/tasks/{id}
```

## Next Steps

- Add input validation
- Implement database persistence
- Add authentication and authorization
- Write unit and integration tests
- Add API documentation with OpenAPI/Swagger

## Conclusion

You've successfully built a complete RESTful API with Ligero Framework! This example demonstrates the core concepts of building APIs with Ligero, including routing, request handling, and response formatting.
