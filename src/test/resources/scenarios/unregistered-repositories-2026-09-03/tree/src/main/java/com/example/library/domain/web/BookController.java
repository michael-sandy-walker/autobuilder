package com.example.library.domain.web;

import com.example.library.domain.model.Book;
import com.example.library.domain.repository.BookRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/books")
public class BookController {

    private final BookRepository bookRepository;

    public BookController(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }

    @PostMapping
    public ResponseEntity<?> createBook(@RequestBody Book book) {
        if (book.getTitle() == null || book.getTitle().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "title is required"));
        }
        if (book.getTitle().length() > 120) {
            return ResponseEntity.badRequest().body(Map.of("message", "title must be at most 120 characters"));
        }
        if (book.getIsbn() == null || book.getIsbn().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "isbn is required"));
        }
        if (!book.getIsbn().matches("\\d{13}")) {
            return ResponseEntity.badRequest().body(Map.of("message", "isbn must be 13 digits"));
        }
        Book saved = bookRepository.save(book);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @GetMapping
    public ResponseEntity<List<Book>> listBooks() {
        return ResponseEntity.ok(bookRepository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getBookById(@PathVariable Long id) {
        Optional<Book> book = bookRepository.findById(id);
        if (book.isPresent()) {
            return ResponseEntity.ok(book.get());
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Book not found"));
    }
}