package com.ligero.http;

import java.io.InputStream;
import java.util.Map;

/**
 * Representa una petición HTTP.
 */
public interface HttpRequest {
    /**
     * Obtiene el método HTTP (GET, POST, etc.).
     */
    String getMethod();
    
    /**
     * Obtiene la URI de la petición.
     */
    String getUri();
    
    /**
     * Obtiene la versión del protocolo HTTP.
     */
    String getProtocol();
    
    /**
     * Obtiene los encabezados de la petición.
     */
    Map<String, String> getHeaders();
    
    /**
     * Obtiene los parámetros de consulta (query parameters).
     */
    Map<String, String> getQueryParams();
    
    /**
     * Obtiene el cuerpo de la petición como un flujo de entrada.
     */
    InputStream getBody();
    
    /**
     * Obtiene el cuerpo de la petición como una cadena.
     */
    String getBodyAsString();
    
    /**
     * Obtiene los parámetros de ruta (path parameters).
     * @return Mapa de parámetros de ruta
     */
    default Map<String, String> getPathParams() {
        return Map.of();
    }
}
