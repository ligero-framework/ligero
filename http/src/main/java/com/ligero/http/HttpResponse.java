package com.ligero.http;

import java.io.OutputStream;
import java.util.Map;

/**
 * Representa una respuesta HTTP.
 */
public interface HttpResponse {
    /**
     * Establece el código de estado de la respuesta.
     * @param statusCode El código de estado HTTP
     * @return Esta instancia para encadenamiento de métodos
     */
    HttpResponse status(int statusCode);
    
    /**
     * Establece un encabezado HTTP.
     * @param name Nombre del encabezado
     * @param value Valor del encabezado
     * @return Esta instancia para encadenamiento de métodos
     */
    HttpResponse header(String name, String value);
    
    /**
     * Establece el tipo de contenido de la respuesta.
     * @param contentType El tipo de contenido (ej. "application/json")
     * @return Esta instancia para encadenamiento de métodos
     */
    HttpResponse contentType(String contentType);
    
    /**
     * Envía una respuesta con el cuerpo especificado.
     * @param body El cuerpo de la respuesta
     * @return Esta instancia para encadenamiento de métodos
     */
    HttpResponse send(String body);
    
    /**
     * Envía una respuesta en formato JSON.
     * @param object El objeto a serializar a JSON
     * @return Esta instancia para encadenamiento de métodos
     */
    HttpResponse json(Object object);
    
    /**
     * Obtiene el flujo de salida para escribir la respuesta.
     * Útil para enviar respuestas en trozos o para streaming.
     */
    OutputStream getOutputStream();
    
    /**
     * Redirige la solicitud a la URL especificada.
     * @param url La URL a la que redirigir
     * @return Esta instancia para encadenamiento de métodos
     */
    HttpResponse redirect(String url);
    
    /**
     * Finaliza la respuesta.
     */
    void end();
}
