package com.ligero.processor.e2e.client;

import com.ligero.Modules;
import com.ligero.client.ClientResponse;
import com.ligero.client.HttpStatusException;
import com.ligero.client.RestClient;
import com.ligero.generated.GeneratedModules;
import com.ligero.processor.e2e.Book;
import com.ligero.processor.e2e.Genre;
import com.ligero.processor.e2e.NewBook;
import com.ligero.test.LigeroTest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The processor-generated LibraryApiClient talks to the typed BookController over real HTTP. */
class GeneratedClientE2ETest {

    private static LigeroTest server;
    private static LibraryApi library;

    @BeforeAll
    static void start() {
        server = LigeroTest.create(app -> Modules.install(app, GeneratedModules.all()));
        library = new LibraryApiClient(RestClient.builder().baseUrl(server.baseUrl()).build());
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void everyKindOfCall() {
        Book created = library.create(new NewBook("Foundation", Genre.FICTION));
        assertThat(created.title()).isEqualTo("Foundation");

        assertThat(library.find(created.id())).contains(created);
        assertThat(library.get(created.id())).isEqualTo(created);
        assertThat(library.getAsText(created.id())).contains("\"title\":\"Foundation\"");
        assertThat(library.list(1000)).contains(created);
        assertThat(library.distinct(1000)).contains(created);
        assertThat(library.distinct(null)).isNotEmpty();

        library.delete(created.id());
        assertThat(library.find(created.id())).isEmpty();
        assertThatThrownBy(() -> library.get(created.id()))
            .isInstanceOfSatisfying(HttpStatusException.class, e -> assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(() -> library.delete(created.id())).isInstanceOf(HttpStatusException.class);
    }

    @Test
    void rawResponsesAndDefaultMethods() {
        ClientResponse invalid = library.createRaw(new NewBook(" ", null), "trace-1");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(library.createNamed("Dune").title()).isEqualTo("Dune");
    }
}
