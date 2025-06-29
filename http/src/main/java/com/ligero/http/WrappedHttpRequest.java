package com.ligero.http;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Implementación de HttpRequest que envuelve otra instancia y permite modificar la URI.
 */
public class WrappedHttpRequest implements HttpRequest {
    private final HttpRequest delegate;
    private final String path;
    private final Map<String, String> pathParams;
    
    /**
     * Crea una nueva instancia de WrappedHttpRequest.
     * @param delegate La instancia de HttpRequest a envolver
     * @param path La nueva ruta a usar
     */
    public WrappedHttpRequest(HttpRequest delegate, String path) {
        this.delegate = delegate;
        this.path = path;
        this.pathParams = new HashMap<>();
    }
    
    @Override
    public String getMethod() {
        return delegate.getMethod();
    }
    
    @Override
    public String getUri() {
        return path;
    }
    
    @Override
    public String getProtocol() {
        return delegate.getProtocol();
    }
    
    @Override
    public Map<String, String> getHeaders() {
        return delegate.getHeaders();
    }
    
    @Override
    public Map<String, String> getQueryParams() {
        return delegate.getQueryParams();
    }
    
    @Override
    public InputStream getBody() {
        return delegate.getBody();
    }
    
    @Override
    public String getBodyAsString() {
        return delegate.getBodyAsString();
    }
    
    @Override
    public Map<String, String> getPathParams() {
        return pathParams;
    }
    
    /**
     * Establece un parámetro de ruta.
     * @param name Nombre del parámetro
     * @param value Valor del parámetro
     */
    public void setPathParam(String name, String value) {
        pathParams.put(name, value);
    }
}
