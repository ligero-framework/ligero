package com.ligero.processor.e2e.versioned;

import com.ligero.beans.stereotype.Controller;
import com.ligero.web.ApiVersion;
import com.ligero.web.Get;
import com.ligero.web.PathParam;

import java.util.Map;

/** Versioned typed routes: one path, a method per API version. */
@Controller("/api/catalog")
public class CatalogController {

    @Get("/{id}")
    @ApiVersion("1")
    Map<String, Object> itemV1(@PathParam long id) {
        return Map.of("id", id, "label", "item " + id);
    }

    @Get("/{id}")
    @ApiVersion("2+")
    Map<String, Object> item(@PathParam long id) {
        return Map.of("id", id, "name", "item " + id, "version", "2+");
    }

    @Get("/{id}")
    String itemWithoutVersion(@PathParam long id) {
        return "unversioned " + id;
    }

    @Get("/legacy")
    @ApiVersion("v1.0")
    String legacy() {
        return "legacy";
    }
}
