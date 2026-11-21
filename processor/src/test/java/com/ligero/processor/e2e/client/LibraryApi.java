package com.ligero.processor.e2e.client;

import com.ligero.client.ClientResponse;
import com.ligero.processor.e2e.Book;
import com.ligero.processor.e2e.NewBook;
import com.ligero.web.Body;
import com.ligero.web.ClientApi;
import com.ligero.web.Delete;
import com.ligero.web.Get;
import com.ligero.web.HeaderParam;
import com.ligero.web.PathParam;
import com.ligero.web.Post;
import com.ligero.web.QueryParam;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** A client of the BookController, implemented by the processor. */
@ClientApi("/api/books")
public interface LibraryApi {

    @Get("/{id}")
    Optional<Book> find(@PathParam long id);

    @Get("/{id}")
    Book get(@PathParam("id") long bookId);

    @Get
    List<Book> list(@QueryParam("limit") int max);

    @Get
    Set<Book> distinct(@QueryParam("limit") Integer max);

    @Post
    ClientResponse createRaw(@Body NewBook book, @HeaderParam("X-Trace") String trace);

    @Post
    Book create(@Body NewBook book);

    @Delete("/{id}")
    void delete(@PathParam long id);

    @Get("/{id}")
    String getAsText(@PathParam long id);

    /** Default methods stay as written. */
    default Book createNamed(String title) {
        return create(new NewBook(title, com.ligero.processor.e2e.Genre.FICTION));
    }
}
