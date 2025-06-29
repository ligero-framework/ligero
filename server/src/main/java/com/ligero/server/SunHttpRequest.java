package com.ligero.server;

import com.ligero.http.HttpRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Implementación de HttpRequest para el servidor HTTP de Sun.
 */
class SunHttpRequest implements HttpRequest {
    private final com.sun.net.httpserver.HttpExchange exchange;
    private String body;
    private Map<String, String> queryParams;
    
    public SunHttpRequest(com.sun.net.httpserver.HttpExchange exchange) {
        this.exchange = exchange;
    }
    
    @Override
    public String getMethod() {
        return exchange.getRequestMethod();
    }
    
    @Override
    public String getUri() {
        String uri = exchange.getRequestURI().toString();
        System.out.println("URI original: " + uri);
        return uri;
    }
    
    @Override
    public String getProtocol() {
        return exchange.getProtocol();
    }
    
    @Override
    public Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((key, values) -> 
            headers.put(key, String.join(", ", values))
        );
        return headers;
    }
    
    @Override
    public Map<String, String> getQueryParams() {
        if (queryParams == null) {
            queryParams = parseQueryParams();
        }
        return queryParams;
    }
    
    private Map<String, String> parseQueryParams() {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty()) {
            return Collections.emptyMap();
        }
        
        Map<String, String> params = new HashMap<>();
        String[] pairs = query.split("&");
        
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            try {
                String key = idx > 0 ? 
                    URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name()) : pair;
                String value = idx > 0 && pair.length() > idx + 1 ? 
                    URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name()) : null;
                params.put(key, value);
            } catch (UnsupportedEncodingException e) {
                // No debería ocurrir con UTF-8
                throw new RuntimeException("Error al decodificar parámetros de consulta", e);
            }
        }
        
        return params;
    }
    
    @Override
    public InputStream getBody() {
        return exchange.getRequestBody();
    }
    
    @Override
    public String getBodyAsString() {
        if (body == null) {
            try (InputStream is = getBody();
                 ByteArrayOutputStream result = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = is.read(buffer)) != -1) {
                    result.write(buffer, 0, length);
                }
                body = result.toString(StandardCharsets.UTF_8.name());
            } catch (IOException e) {
                throw new RuntimeException("Error al leer el cuerpo de la petición", e);
            }
        }
        return body;
    }
}
