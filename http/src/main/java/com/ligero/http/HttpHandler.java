package com.ligero.http;

import com.ligero.http.HttpRequest;
import com.ligero.http.HttpResponse;

/**
 * Interfaz para manejar peticiones HTTP.
 */
@FunctionalInterface
public interface HttpHandler {
    /**
     * Maneja una petición HTTP y devuelve una respuesta.
     * 
     * @param request La petición HTTP
     * @param response La respuesta HTTP
     */
    void handle(HttpRequest request, HttpResponse response);
}
