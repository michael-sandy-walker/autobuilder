package com.example.library.domain.store;

import com.example.library.domain.model.Book;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class BookStore {
    private final Map<Long, Book> books = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(0);

    public Book save(Book book) {
        book.setId(idGen.incrementAndGet());
        books.put(book.getId(), book);
        return book;
    }

    public List<Book> findAll() {
        return List.copyOf(books.values());
    }

    public Optional<Book> findById(Long id) {
        return Optional.ofNullable(books.get(id));
    }
}
