package com.ligero.processor.e2e;

import com.ligero.beans.stereotype.Controller;
import com.ligero.http.Context;
import com.ligero.http.NotFoundException;
import com.ligero.http.Response;
import com.ligero.web.Body;
import com.ligero.web.Delete;
import com.ligero.web.Get;
import com.ligero.web.HeaderParam;
import com.ligero.web.PathParam;
import com.ligero.web.Post;
import com.ligero.web.QueryParam;
import com.ligero.web.Route;
import com.ligero.web.Status;
import com.ligero.web.Valid;

import java.util.List;
import java.util.Optional;

/** A typed controller exactly as an application would write it. */
@Controller("/api/books")
public class BookController {

    private final BookStore store;

    public BookController(BookStore store) {
        this.store = store;
    }

    @Get
    List<Book> list(@QueryParam Genre genre, @QueryParam(defaultValue = "50") int limit) {
        return store.all().stream()
            .filter(book -> genre == null || book.genre() == genre)
            .sorted(java.util.Comparator.comparingLong(Book::id))
            .limit(limit)
            .toList();
    }

    @Get("/{id}")
    Optional<Book> get(@PathParam long id) {
        return store.find(id);
    }

    @Post
    Response<Book> create(@Body @Valid NewBook body) {
        Book created = store.add(body.title(), body.genre());
        return Response.created("/api/books/" + created.id(), created);
    }

    @Post("/quick")
    @Status(201)
    Book quick(@QueryParam String title) {
        return store.add(title, Genre.FICTION);
    }

    @Delete("/{id}")
    void delete(@PathParam long id) {
        if (!store.remove(id)) {
            throw new NotFoundException("Book " + id + " does not exist");
        }
    }

    @Get("/{id}/title")
    String title(@PathParam long id) {
        return store.find(id).map(Book::title).orElseThrow(() -> new NotFoundException("No such book"));
    }

    @Get("/whoami")
    String whoami(@HeaderParam("X-Tenant") String tenant, @HeaderParam(value = "X-Page", defaultValue = "1") int page) {
        return tenant + ":" + page;
    }

    @Get("/tags")
    List<String> tags(@QueryParam("tag") List<String> tags) {
        return tags;
    }

    @Route(method = "QUERY", path = "/search")
    List<Book> search(@Body String needle) {
        return store.all().stream().filter(book -> book.title().contains(needle)).toList();
    }

    @Get("/manual")
    void manual(Context ctx) {
        ctx.status(418).text("handled by hand");
    }
}
