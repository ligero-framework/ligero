package com.ligero.router;

import com.ligero.http.HttpHandler;
import com.ligero.http.HttpRequest;
import com.ligero.http.HttpResponse;

import java.util.HashMap;
import java.util.Map;
import com.ligero.http.WrappedHttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Collectors;

/**
 * Enrutador simple para manejar rutas HTTP.
 */
public class Router implements HttpHandler {
    private final String contextPath;
    
    /**
     * Crea un nuevo enrutador sin contexto.
     */
    public Router() {
        this("/");
    }
    
    /**
     * Crea un nuevo enrutador con el contexto especificado.
     * @param contextPath El contexto de la aplicación
     */
    public Router(String contextPath) {
        this.contextPath = contextPath != null ? contextPath : "/";
    }
    
    private static class Route {
        private static final String PARAM_PREFIX = "{";
        private static final String PARAM_SUFFIX = "}";

        final String path;
        final HttpHandler handler;
        final String[] pathParts;
        final boolean hasParams;

        Route(String path, HttpHandler handler) {
            this.path = path;
            this.handler = handler;
            this.pathParts = path.split("/");
            this.hasParams = path.contains(PARAM_PREFIX) && path.contains(PARAM_SUFFIX);
        }

        boolean matches(String requestPath, Map<String, String> params) {
            // Normalizar la ruta solicitada
            if (requestPath == null) {
                requestPath = "/";
            } else if (requestPath.isEmpty()) {
                requestPath = "/";
            } else if (!requestPath.startsWith("/")) {
                requestPath = "/" + requestPath;
            }

            // Manejar el caso especial de la ruta raíz
            if ("/".equals(path)) {
                boolean matches = "/".equals(requestPath) || "".equals(requestPath);
                System.out.println("Comparando ruta raíz: '" + path + "' con '" + requestPath + "' -> " + (matches ? "COINCIDE" : "no coincide"));
                return matches;
            }

            // Si la ruta es exacta, coincidencia directa
            if (!hasParams && path.equals(requestPath)) {
                System.out.println("Ruta exacta coincide: " + path);
                return true;
            }

            // Si la ruta solicitada es la raíz pero esta no es la ruta raíz
            if ("/".equals(requestPath) || "".equals(requestPath)) {
                System.out.println("Ruta solicitada es raíz pero esta no es la ruta raíz");
                return false;
            }

            // Dividir la ruta de la petición en partes
            String[] requestParts = requestPath.split("/");

            // Las rutas deben tener el mismo número de partes
            if (pathParts.length != requestParts.length) {
                System.out.println("Número de partes no coincide: " + pathParts.length + " != " + requestParts.length);
                return false;
            }

            // Comparar cada parte de la ruta
            for (int i = 0; i < pathParts.length; i++) {
                String pathPart = pathParts[i];
                String requestPart = requestParts[i];

                // Si es un parámetro (ej: {id})
                if (pathPart.startsWith(PARAM_PREFIX) && pathPart.endsWith(PARAM_SUFFIX)) {
                    String paramName = pathPart.substring(1, pathPart.length() - 1);
                    params.put(paramName, requestPart);
                }
                // Si no es un parámetro, las partes deben coincidir exactamente
                else if (!pathPart.equals(requestPart)) {
                    return false;
                }
            }

            return true;
        }
    }


    private final Map<String, List<Route>> routes = new HashMap<>();
    private HttpHandler fallbackHandler;

    /**
     * Registra un manejador para una ruta GET.
     * @param path Ruta de la petición
     * @param handler Manejador de la petición
     */
    public void get(String path, BiConsumer<HttpRequest, HttpResponse> handler) {
        addRoute("GET", path, handler::accept);
    }

    /**
     * Registra un manejador para una ruta POST.
     * @param path Ruta de la petición
     * @param handler Manejador de la petición
     */
    public void post(String path, BiConsumer<HttpRequest, HttpResponse> handler) {
        addRoute("POST", path, handler::accept);
    }

    /**
     * Registra un manejador para una ruta PUT.
     * @param path Ruta de la petición
     * @param handler Manejador de la petición
     */
    public void put(String path, BiConsumer<HttpRequest, HttpResponse> handler) {
        addRoute("PUT", path, handler::accept);
    }

    /**
     * Registra un manejador para una ruta DELETE.
     * @param path Ruta de la petición
     * @param handler Manejador de la petición
     */
    public void delete(String path, BiConsumer<HttpRequest, HttpResponse> handler) {
        addRoute("DELETE", path, handler::accept);
    }

    /**
     * Configura un manejador para todas las rutas que no coincidan con ninguna ruta definida.
     * @param handler Manejador de la petición
     */
    public void fallback(BiConsumer<HttpRequest, HttpResponse> handler) {
        this.fallbackHandler = handler::accept;
    }

    /**
     * Obtiene todas las rutas registradas organizadas por método HTTP.
     * @return Mapa de métodos HTTP a listas de rutas
     */
    public Map<String, List<String>> getRoutes() {
        Map<String, List<String>> result = new HashMap<>();
        routes.forEach((method, routeList) -> {
            List<String> paths = routeList.stream()
                .map(route -> route.path)
                .collect(Collectors.toList());
            result.put(method, paths);
        });
        return result;
    }

    /**
     * Maneja una petición HTTP enrutándola al manejador correspondiente.
     */
    @Override
    public void handle(HttpRequest request, HttpResponse response) {
        try {
            String method = request.getMethod().toUpperCase();
            String uri = request.getUri();

            System.out.println("=== INICIO DE MANEJO DE SOLICITUD ===");
            System.out.println("URI recibida: " + uri);

            // Eliminar parámetros de consulta si existen
            String path = uri.split("\\?")[0];

            System.out.println("Ruta sin parámetros: " + path);

            // Normalizar la ruta
            path = normalizePath(path);

            System.out.println("Ruta normalizada: " + path);

            // Normalizar la ruta para comparación (eliminar el contexto si es necesario)
            String pathForMatching = normalizePathForMatching(path);

            System.out.println("Ruta para coincidencia: " + pathForMatching);
            System.out.println("Método: " + method);

            // Mostrar información de depuración
            System.out.println("\nBuscando ruta para: " + method + " " + pathForMatching);
            System.out.println("Rutas registradas para " + method + ":");

            // Buscar el manejador específico para el método y la ruta
            List<Route> methodRoutes = routes.getOrDefault(method, List.of());
            HttpHandler handler = null;

            // Mostrar todas las rutas registradas para este método
            if (methodRoutes.isEmpty()) {
                System.out.println("  No hay rutas registradas para el método " + method);
            } else {
                for (Route route : methodRoutes) {
                    System.out.println("  - " + route.path + (route.hasParams ? " (con parámetros)" : ""));
                }
            }

            // Buscar una ruta que coincida
            for (Route route : methodRoutes) {
                Map<String, String> params = new HashMap<>();
                boolean matches = route.matches(pathForMatching, params);
                System.out.println("Probando ruta: " + route.path + " -> " + (matches ? "COINCIDE" : "no coincide"));

                if (matches) {
                    // Si es una ruta con parámetros, configurar los parámetros en la solicitud
                    if (request instanceof WrappedHttpRequest) {
                        params.forEach(((WrappedHttpRequest) request)::setPathParam);
                    }
                    handler = route.handler;
                    System.out.println("Manejador encontrado para la ruta: " + route.path);
                    break;
                }
            }

            // Si se encontró un manejador, usarlo
            if (handler != null) {
                handler.handle(request, response);
            }
            // Si hay un manejador de respaldo, usarlo
            else if (fallbackHandler != null) {
                fallbackHandler.handle(request, response);
            }
            // Si no hay manejador, responder con 404
            else {
                response.status(404).send("Ruta no encontrada: " + path);
                System.err.println("Ruta no encontrada: " + method + " " + path);
            }
        } catch (Exception e) {
            System.err.println("Error al manejar la solicitud: " + e.getMessage());
            e.printStackTrace();
            response.status(500).send("Error interno del servidor: " + e.getMessage());
        }
    }

    /**
     * Normaliza una ruta para asegurar que no tenga barras diagonales duplicadas
     * y que comience con una barra diagonal.
     */
    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }

        // Asegurarse de que la ruta comience con /
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        // Eliminar barras diagonales duplicadas
        path = path.replaceAll("//+/", "/");

        // Eliminar la barra diagonal final si hay más de un carácter
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        return path;
    }

    /**
     * Normaliza una ruta para comparación, eliminando el contexto si es necesario.
     * @param path La ruta a normalizar
     * @return La ruta normalizada
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
     * Agrega una ruta al enrutador.
     */
    private void addRoute(String method, String path, HttpHandler handler) {
        String normalizedPath = normalizePath(path);
        routes.computeIfAbsent(method.toUpperCase(), k -> new ArrayList<>())
              .add(new Route(normalizedPath, handler));

        // Mostrar información de depuración
        System.out.println("Ruta registrada: " + method.toUpperCase() + " " + normalizedPath);
    }
}
