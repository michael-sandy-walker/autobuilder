package com.example.library.domain.controller;

import com.example.library.domain.model.Book;
import com.example.library.domain.service.BookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/books")
public class BookController {

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @PostMapping
    public ResponseEntity<Book> create(@RequestBody Map<String, String> body) {
        Book book = bookService.create(body.get("title"), body.get("isbn"));
        return ResponseEntity.status(201).body(book);
    }

    @GetMapping
    public ResponseEntity<List<Book>> list() {
        return ResponseEntity.ok(bookService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Book> get(@PathVariable Long id) {
        Book book = bookService.findById(id)
                .orElseThrow(() -> new com.example.library.domain.exception.NotFoundException("Book not found with id " + id));
        return ResponseEntity.ok(book);
    }
}