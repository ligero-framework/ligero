package com.ligero.processor.e2e;

import com.ligero.beans.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class BookStore {

    private final Map<Long, Book> books = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    public Book add(String title, Genre genre) {
        Book book = new Book(ids.incrementAndGet(), title, genre);
        books.put(book.id(), book);
        return book;
    }

    public Optional<Book> find(long id) {
        return Optional.ofNullable(books.get(id));
    }

    public List<Book> all() {
        return new ArrayList<>(books.values());
    }

    public boolean remove(long id) {
        return books.remove(id) != null;
    }
}
