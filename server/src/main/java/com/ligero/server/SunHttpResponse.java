package com.ligero.server;

import com.ligero.http.HttpResponse;
import com.ligero.json.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Implementación de HttpResponse para el servidor HTTP de Sun.
 */
class SunHttpResponse implements HttpResponse {
    private final com.sun.net.httpserver.HttpExchange exchange;
    private String contentType = "text/plain";
    private int statusCode = 200;
    private boolean headersSent = false;
    
    public SunHttpResponse(com.sun.net.httpserver.HttpExchange exchange) {
        this.exchange = exchange;
    }
    
    @Override
    public HttpResponse status(int statusCode) {
        this.statusCode = statusCode;
        return this;
    }
    
    @Override
    public HttpResponse header(String name, String value) {
        exchange.getResponseHeaders().set(name, value);
        return this;
    }
    
    @Override
    public HttpResponse contentType(String contentType) {
        this.contentType = contentType;
        return this;
    }
    
    @Override
    public HttpResponse send(String body) {
        try {
            if (!headersSent) {
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(statusCode, body == null ? -1 : body.getBytes(StandardCharsets.UTF_8).length);
                headersSent = true;
            }
            
            if (body != null) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error al enviar respuesta", e);
        }
        return this;
    }
    
    @Override
    public HttpResponse json(Object object) {
        contentType("application/json");
        return send(Json.stringify(object));
    }
    
    @Override
    public OutputStream getOutputStream() {
        try {
            if (!headersSent) {
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(statusCode, 0);
                headersSent = true;
            }
            return exchange.getResponseBody();
        } catch (IOException e) {
            throw new RuntimeException("Error al obtener el flujo de salida", e);
        }
    }
    
    @Override
    public HttpResponse redirect(String url) {
        try {
            exchange.getResponseHeaders().set("Location", url);
            exchange.sendResponseHeaders(302, -1);
            if (!headersSent) {
                headersSent = true;
            }
        } catch (IOException e) {
            // Si hay un error al redirigir, lanzar una excepción de tiempo de ejecución
            throw new RuntimeException("Error al redirigir a " + url, e);
        }
        return this;
    }
    
    @Override
    public void end() {
        if (!headersSent) {
            try {
                exchange.sendResponseHeaders(statusCode, -1);
                headersSent = true;
            } catch (IOException e) {
                throw new RuntimeException("Error al finalizar la respuesta", e);
            }
        }
        exchange.close();
    }
}
