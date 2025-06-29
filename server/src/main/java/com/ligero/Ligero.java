package com.ligero;

import com.ligero.http.HttpHandler;
import com.ligero.http.HttpRequest;
import com.ligero.http.HttpResponse;
import com.ligero.router.Router;

import com.ligero.server.HttpServer;
import java.io.IOException;
import com.ligero.http.WrappedHttpRequest;
import java.util.function.BiConsumer;

/**
 * Clase principal del framework Ligero.
 * Proporciona métodos para configurar y ejecutar una aplicación web ligera.
 */
public class Ligero implements AutoCloseable {
    private final int port;
    private final String host;
    private final String contextPath;
    private HttpServer server;
    private final Router router;

    private Ligero(String host, int port, String contextPath) {
        this.host = host;
        this.port = port;
        
        // Normalizar el contextPath
        String normalizedContextPath;
        if (contextPath == null || contextPath.trim().isEmpty()) {
            normalizedContextPath = "/";
        } else {
            // Asegurarse de que el contextPath empiece con /
            normalizedContextPath = contextPath.startsWith("/") ? contextPath : "/" + contextPath;
            // Asegurarse de que no termine con /
            if (normalizedContextPath.endsWith("/") && normalizedContextPath.length() > 1) {
                normalizedContextPath = normalizedContextPath.substring(0, normalizedContextPath.length() - 1);
            }
        }
        
        this.contextPath = normalizedContextPath;
        this.router = new Router(this.contextPath);
        System.out.println("Contexto configurado: '" + this.contextPath + "'");
    }

    /**
     * Crea una nueva instancia de la aplicación Ligero.
     * @param port Puerto en el que se ejecutará el servidor
     * @return Una nueva instancia de Ligero
     */
    public static Ligero create(int port) {
        return new Ligero("0.0.0.0", port, "/");
    }

    /**
     * Crea una nueva instancia de la aplicación Ligero con un contexto específico.
     * @param port Puerto en el que se ejecutará el servidor
     * @param contextPath Ruta base para todas las rutas
     * @return Una nueva instancia de Ligero
     */
    public static Ligero create(int port, String contextPath) {
        return new Ligero("0.0.0.0", port, contextPath);
    }

    /**
     * Inicia el servidor.
     * @throws IOException Si hay un error al iniciar el servidor
     */
    public void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("El servidor ya está en ejecución");
        }

        // Crear el servidor con el contexto y el enrutador especificados
        this.server = new HttpServer(port, contextPath, Runtime.getRuntime().availableProcessors(), router);

        // Configurar manejador de rutas no encontradas
        router.fallback((req, res) -> {
            String path = req.getUri();
            String normalizedPath = normalizePathForMatching(path);
            System.out.println("Ruta no encontrada: " + path + " (normalizada: " + normalizedPath + ")");
            res.status(404).send("Ruta no encontrada: " + path);
        });
        
        // Agregar un manejador para la ruta raíz del contexto
        if (!"/".equals(contextPath)) {
            router.get(contextPath, (req, res) -> {
                // Redirigir a la ruta raíz sin el contexto
                res.redirect("/");
            });
        }

        // Iniciar el servidor
        server.start();
        System.out.println("Servidor Ligero iniciado en http://" + host + ":" + port + contextPath);
        System.out.println("Rutas disponibles:");
        // Mostrar rutas registradas
        router.getRoutes().forEach((method, paths) -> {
            paths.forEach(path -> {
                System.out.println("  " + method + " " + path);
            });
        });
    }

    /**
     * Detiene el servidor de manera segura.
     */
    public void stop() {
        if (server != null) {
            try {
                // Detener el servidor con un pequeño retraso para permitir que las conexiones actuales se completen
                server.stop(1); // 1 segundo de retraso
                System.out.println("Servidor Ligero detenido");
            } catch (Exception e) {
                System.err.println("Error al detener el servidor: " + e.getMessage());
                // Intentar forzar la detención si hay un error
                try {
                    if (server != null) {
                        server.stop(0);
                    }
                } catch (Exception ex) {
                    System.err.println("Error al forzar la detención del servidor: " + ex.getMessage());
                }
            } finally {
                server = null;
            }
        }
    }

    /**
     * Detiene el servidor después de un retraso.
     * @param delay Tiempo de espera en segundos antes de detener el servidor
     * @deprecated Usar stop() en su lugar
     */
    @Deprecated
    public void stop(int delay) {
        stop();
    }

    /**
     * Prepara una ruta para comparación, eliminando el contexto si es necesario.
     * @param path Ruta original (puede incluir el contexto)
     * @return Ruta normalizada sin el contexto
     */
    private String normalizePathForMatching(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        
        // Asegurarse de que la ruta comience con /
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        
        // Si la ruta comienza con el contexto, quitarlo
        if (!"/".equals(contextPath) && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
            if (path.isEmpty()) {
                path = "/";
            }
        }
        
        return path;
    }
    
    /**
     * Configura una ruta GET.
     * @param path Ruta de la petición (sin el contexto)
     * @param handler Manejador de la petición
     * @return Esta instancia para encadenamiento de métodos
     */
    public Ligero get(String path, BiConsumer<HttpRequest, HttpResponse> handler) {
        // No necesitamos agregar el contexto aquí, ya que se manejará en el enrutamiento
        System.out.println("Registrando ruta GET: " + path);
        router.get(path, handler::accept);
        return this;
    }
    
    /**
     * Configura una ruta POST.
     * @param path Ruta de la petición (sin el contexto)
     * @param handler Manejador de la petición
     * @return Esta instancia para encadenamiento de métodos
     */
    public Ligero post(String path, BiConsumer<HttpRequest, HttpResponse> handler) {
        // No necesitamos agregar el contexto aquí, ya que se manejará en el enrutamiento
        System.out.println("Registrando ruta POST: " + path);
        router.post(path, handler::accept);
        return this;
    }
    
    /**
     * Configura una ruta PUT.
     * @param path Ruta de la petición (sin el contexto)
     * @param handler Manejador de la petición
     * @return Esta instancia para encadenamiento de métodos
     */
    public Ligero put(String path, BiConsumer<HttpRequest, HttpResponse> handler) {
        // No necesitamos agregar el contexto aquí, ya que se manejará en el enrutamiento
        System.out.println("Registrando ruta PUT: " + path);
        router.put(path, handler::accept);
        return this;
    }
    
    /**
     * Configura una ruta DELETE.
     * @param path Ruta de la petición (sin el contexto)
     * @param handler Manejador de la petición
     * @return Esta instancia para encadenamiento de métodos
     */
    public Ligero delete(String path, BiConsumer<HttpRequest, HttpResponse> handler) {
        // No necesitamos agregar el contexto aquí, ya que se manejará en el enrutamiento
        System.out.println("Registrando ruta DELETE: " + path);
        router.delete(path, handler::accept);
        return this;
    }

    /**
     * Configura un manejador para todas las rutas que no coincidan con ninguna ruta definida.
     * @param handler Manejador de la petición
     * @return Esta instancia para encadenamiento de métodos
     */
    public Ligero fallback(BiConsumer<HttpRequest, HttpResponse> handler) {
        System.out.println("Registrando manejador de rutas no encontradas");
        router.fallback(handler::accept);
        return this;
    }

    /**
     * Obtiene el servidor HTTP subyacente.
     * @return El servidor HTTP
     */
    public HttpServer getServer() {
        return server;
    }
    
    /**
     * Obtiene el contexto de la aplicación.
     * @return El contexto de la aplicación
     */
    public String getContextPath() {
        return contextPath;
    }

    @Override
    public void close() {
        stop();
    }
}
