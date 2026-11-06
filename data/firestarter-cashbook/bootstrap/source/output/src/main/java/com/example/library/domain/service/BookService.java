package com.example.library.domain.service;

import com.example.library.domain.exception.ValidationException;
import com.example.library.domain.model.Book;
import com.example.library.domain.repository.BookRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class BookService {

    private static final Pattern ISBN_PATTERN = Pattern.compile("\\d{13}");
    private static final int MAX_TITLE_LENGTH = 120;

    private final BookRepository bookRepository;

    public BookService(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }

    public Book create(String title, String isbn) {
        if (title == null || title.isBlank()) {
            throw new ValidationException("title is required");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new ValidationException("title must not exceed 120 characters");
        }
        if (isbn == null || isbn.isBlank()) {
            throw new ValidationException("isbn is required");
        }
        if (!ISBN_PATTERN.matcher(isbn).matches()) {
            throw new ValidationException("isbn must match \\d{13}");
        }
        Book book = new Book(null, title, isbn);
        return bookRepository.save(book);
    }

    public List<Book> list() {
        return bookRepository.findAll();
    }

    public Optional<Book> findById(Long id) {
        return bookRepository.findById(id);
    }
}