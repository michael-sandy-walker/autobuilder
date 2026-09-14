package com.example.library.domain.repository;

import com.example.library.domain.Book;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public class BookRepository {

    private final List<Book> books = new ArrayList<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    public Book save(Book book) {
        book.setId(idGenerator.incrementAndGet());
        books.add(book);
        return book;
    }

    public List<Book> findAll() {
        return new ArrayList<>(books);
    }

    public Optional<Book> findById(Long id) {
        return books.stream()
                .filter(b -> b.getId().equals(id))
                .findFirst();
    }
}