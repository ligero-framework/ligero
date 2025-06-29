package com.ligero.server;

import com.ligero.http.HttpHandler;
import com.ligero.http.HttpRequest;
import com.ligero.http.HttpResponse;
import com.ligero.router.Router;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Servidor HTTP simple basado en el servidor HTTP de Java.
 */
public class HttpServer {
    private final com.sun.net.httpserver.HttpServer server;
    private final Router router;
    private final Executor executor;
    
    /**
     * Crea una nueva instancia del servidor HTTP.
     * @param port Puerto en el que escuchará el servidor
     * @param contextPath Ruta base para todas las rutas
     * @throws IOException Si hay un error al crear el servidor
     */
    public HttpServer(int port, String contextPath) throws IOException {
        this(port, contextPath, Runtime.getRuntime().availableProcessors());
    }
    
    /**
     * Crea una nueva instancia del servidor HTTP con un número específico de hilos.
     * @param port Puerto en el que escuchará el servidor
     * @param contextPath Ruta base para todas las rutas
     * @param threads Número de hilos para el pool de ejecución
     * @throws IOException Si hay un error al crear el servidor
     */
    public HttpServer(int port, String contextPath, int threads) throws IOException {
        this(port, contextPath, threads, new Router());
    }
    
    public HttpServer(int port, String contextPath, int threads, Router router) throws IOException {
        if (router == null) {
            throw new IllegalArgumentException("El enrutador no puede ser nulo");
        }
        
        this.router = router;
        this.server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
        this.executor = Executors.newFixedThreadPool(threads);
        
        // Asegurarse de que el contextPath sea válido
        String normalizedContextPath;
        if (contextPath == null || contextPath.trim().isEmpty() || "/".equals(contextPath)) {
            normalizedContextPath = "/";
        } else {
            // Asegurarse de que el contextPath empiece con /
            normalizedContextPath = contextPath.startsWith("/") ? contextPath : "/" + contextPath;
            // Asegurarse de que no termine con /
            if (normalizedContextPath.endsWith("/") && normalizedContextPath.length() > 1) {
                normalizedContextPath = normalizedContextPath.substring(0, normalizedContextPath.length() - 1);
            }
        }
        
        // Configurar el manejador de peticiones
        System.out.println("Iniciando servidor en contexto: " + normalizedContextPath);
        server.createContext(normalizedContextPath, exchange -> {
            // Crear wrappers para la petición y respuesta
            HttpRequest request = new SunHttpRequest(exchange);
            HttpResponse response = new SunHttpResponse(exchange);
            
            try {
                // Enrutar la petición
                router.handle(request, response);
            } catch (Exception e) {
                // Manejar errores inesperados
                response.status(500).send("Error interno del servidor: " + e.getMessage());
            } finally {
                exchange.close();
            }
        });
        
        // Configurar el executor
        server.setExecutor(executor);
    }
    
    /**
     * Obtiene el enrutador del servidor.
     */
    public Router getRouter() {
        return router;
    }
    

    
    /**
     * Inicia el servidor.
     */
    public void start() {
        server.start();
        System.out.println("Servidor iniciado en el puerto " + server.getAddress().getPort());
    }
    
    /**
     * Detiene el servidor.
     * @param delay Tiempo de espera en segundos antes de detener el servidor
     */
    public void stop(int delay) {
        if (server != null) {
            // Detener el servidor
            server.stop(delay);
            System.out.println("Servidor detenido");
            
            // Cerrar el ExecutorService
            if (executor instanceof java.util.concurrent.ExecutorService) {
                java.util.concurrent.ExecutorService es = (java.util.concurrent.ExecutorService) executor;
                es.shutdown();
                try {
                    if (!es.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        es.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    es.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
