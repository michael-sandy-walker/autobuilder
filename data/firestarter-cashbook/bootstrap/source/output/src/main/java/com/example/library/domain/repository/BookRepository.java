package com.example.library.domain.repository;

import com.example.library.domain.model.Book;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class BookRepository {
    private final Map<Long, Book> store = new LinkedHashMap<>();
    private final AtomicLong idGen = new AtomicLong(0);

    public Book save(Book book) {
        if (book.getId() == null) {
            book.setId(idGen.incrementAndGet());
        }
        store.put(book.getId(), book);
        return book;
    }

    public List<Book> findAll() {
        return new ArrayList<>(store.values());
    }

    public Optional<Book> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }
}