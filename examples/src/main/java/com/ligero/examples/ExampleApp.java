package com.ligero.examples;

import com.ligero.Ligero;

import java.io.IOException;
import java.util.Map;

/**
 * Ejemplo de una aplicación simple usando el framework Ligero.
 */
public class ExampleApp {
    public static void main(String[] args) {
        try {
            // Crear una instancia de la aplicación Ligero con contexto /api
            Ligero app = Ligero.create(8082, "/api");

            // Configurar rutas
            app.get("/", (req, res) -> {
                String html = "<!DOCTYPE html>\n" +
                    "<html lang=\"es\">\n" +
                    "<head>\n" +
                    "    <meta charset=\"UTF-8\">\n" +
                    "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                    "    <title>Ligero Framework</title>\n" +
                    "    <style>\n" +
                    "        body { font-family: Arial, sans-serif; line-height: 1.6; margin: 0; padding: 20px; }\n" +
                    "        .container { max-width: 800px; margin: 0 auto; }\n" +
                    "        h1 { color: #2c3e50; }\n" +
                    "        ul { list-style-type: none; padding: 0; }\n" +
                    "        li { margin: 10px 0; }\n" +
                    "        a { color: #3498db; text-decoration: none; }\n" +
                    "        a:hover { text-decoration: underline; }\n" +
                    "        .endpoint { background-color: #f8f9fa; padding: 5px 10px; border-radius: 4px; font-family: monospace; }\n" +
                    "    </style>\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "    <div class=\"container\">\n" +
                    "        <h1>¡Bienvenido a Ligero Framework!</h1>\n" +
                    "        <p>Prueba estas rutas:</p>\n" +
                    "        <ul>\n" +
                    "            <li>\n" +
                    "                <a href='/api/saludo/Mundo'>Saludo</a> - \n" +
                    "                <span class=\"endpoint\">GET /api/saludo/{nombre}</span>\n" +
                    "            </li>\n" +
                    "            <li>\n" +
                    "                Envía un POST a <span class=\"endpoint\">/api/datos</span> con un cuerpo JSON\n" +
                    "                <pre>{\n    \"mensaje\": \"Hola\"\n}</pre>\n" +
                    "            </li>\n" +
                    "            <li>\n" +
                    "                <a href='/api/no-existe'>Ruta no encontrada</a> - \n" +
                    "                <span class=\"endpoint\">GET /api/no-existe</span>\n" +
                    "            </li>\n" +
                    "        </ul>\n" +
                    "    </div>\n" +
                    "</body>\n" +
                    "</html>";

                res.contentType("text/html; charset=utf-8")
                   .send(html);
            });

            app.get("/saludo/{nombre}", (req, res) -> {
                String nombre = req.getPathParams().get("nombre");
                res.json(Map.of(
                    "mensaje", "¡Hola, " + nombre + "!",
                    "timestamp", System.currentTimeMillis()
                ));
            });

            app.post("/datos", (req, res) -> {
                String body = req.getBodyAsString();
                res.json(Map.of(
                    "status", "recibido",
                    "data", body,
                    "timestamp", System.currentTimeMillis()
                ));
            });

            // Manejador para rutas no encontradas
            app.fallback((req, res) -> {
                res.status(404).json(Map.of(
                    "error", "Ruta no encontrada",
                    "path", req.getUri(),
                    "method", req.getMethod()
                ));
            });


            // Iniciar el servidor
            app.start();

            // Mensaje de inicio
            System.out.println("Servidor iniciado en http://localhost:8082/api");
            System.out.println("Prueba las siguientes rutas:");
            System.out.println("  - GET  http://localhost:8082/api");
            System.out.println("  - GET  http://localhost:8082/api/saludo/Mundo");
            System.out.println("  - POST http://localhost:8082/api/datos (con un cuerpo JSON)");
            System.out.println("  - GET  http://localhost:8082/api/no-existe (para probar el manejador de rutas no encontradas)");
            System.out.println("\nPresiona Ctrl+C para detener el servidor");

            // Configurar el manejador de señal para Ctrl+C
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nDeteniendo el servidor...");
                app.close();
                System.out.println("Servidor detenido correctamente.");
            }));

            // Mantener la aplicación en ejecución hasta que se presione Ctrl+C
            Thread.currentThread().join();

        } catch (Exception e) {
            System.err.println("Error al iniciar el servidor: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
