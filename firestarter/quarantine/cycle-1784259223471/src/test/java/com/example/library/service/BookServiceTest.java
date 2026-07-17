package com.example.library.service;

import com.example.library.model.Book;
import com.example.library.model.BookRepository;
import com.example.library.model.BookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class BookServiceTest {

    @Mock
    private BookRepository bookRepository;

    @InjectMocks
    private BookService bookService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testSaveBook_WithValidBook_ShouldSaveAndReturnBook() {
        Book book = new Book("Test Book", "1234567890123");
        Book savedBook = new Book(1L, "Test Book", "1234567890123");

        when(bookRepository.findByIsbn("1234567890123")).thenReturn(Optional.empty());
        when(bookRepository.save(book)).thenReturn(savedBook);

        Book result = bookService.saveBook(book);

        assertEquals(savedBook, result);
        verify(bookRepository).save(book);
    }

    @Test
    public void testSaveBook_WithNullTitle_ShouldThrowIllegalArgumentException() {
        Book book = new Book(null, "1234567890123");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> bookService.saveBook(book)
        );

        assertEquals("Title is required", exception.getMessage());
    }

    @Test
    public void testSaveBook_WithEmptyTitle_ShouldThrowIllegalArgumentException() {
        Book book = new Book("", "1234567890123");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> bookService.saveBook(book)
        );

        assertEquals("Title is required", exception.getMessage());
    }

    @Test
    public void testSaveBook_WithTitleExceeding120Chars_ShouldThrowIllegalArgumentException() {
        StringBuilder title = new StringBuilder();
        for (int i = 0; i < 121; i++) {
            title.append("a");
        }
        Book book = new Book(title.toString(), "1234567890123");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> bookService.saveBook(book)
        );

        assertEquals("Title must be at most 120 characters", exception.getMessage());
    }

    @Test
    public void testSaveBook_WithNullIsbn_ShouldThrowIllegalArgumentException() {
        Book book = new Book("Test Book", null);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> bookService.saveBook(book)
        );

        assertEquals("ISBN must be exactly 13 digits", exception.getMessage());
    }

    @Test
    public void testSaveBook_WithInvalidIsbnFormat_ShouldThrowIllegalArgumentException() {
        Book book = new Book("Test Book", "123456789012");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> bookService.saveBook(book)
        );

        assertEquals("ISBN must be exactly 13 digits", exception.getMessage());
    }

    @Test
    public void testSaveBook_WithDuplicateIsbn_ShouldThrowIllegalArgumentException() {
        Book book = new Book("Test Book", "1234567890123");
        Book existingBook = new Book(1L, "Existing Book", "1234567890123");

        when(bookRepository.findByIsbn("1234567890123")).thenReturn(Optional.of(existingBook));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> bookService.saveBook(book)
        );

        assertEquals("ISBN must be unique", exception.getMessage());
    }
}