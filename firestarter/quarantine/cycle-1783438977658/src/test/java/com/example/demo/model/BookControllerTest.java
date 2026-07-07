package com.example.demo.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookControllerTest {

    @Mock
    private BookRepository bookRepository;

    @InjectMocks
    private BookController bookController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void getBookById_WithValidIdAndExistingBook_ReturnsBook() {
        Book book = new Book();
        book.setId(1L);
        book.setTitle("Test Book");
        book.setIsbn("1234567890123");

        when(bookRepository.findById(1L)).thenReturn(book);

        ResponseEntity<Object> response = bookController.getBookById(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof Book);
        assertEquals("Test Book", ((Book) response.getBody()).getTitle());
    }

    @Test
    void getBookById_WithValidIdAndNonExistingBook_ReturnsNotFound() {
        when(bookRepository.findById(1L)).thenReturn(null);

        ResponseEntity<Object> response = bookController.getBookById(1L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void getBookById_WithInvalidId_ReturnsBadRequest() {
        ResponseEntity<Object> response = bookController.getBookById(-1L);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof Map);
        assertEquals("Invalid book ID", ((Map<?, ?>) response.getBody()).get("message"));
    }

    @Test
    void getBookById_WithZeroId_ReturnsBadRequest() {
        ResponseEntity<Object> response = bookController.getBookById(0L);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof Map);
        assertEquals("Invalid book ID", ((Map<?, ?>) response.getBody()).get("message"));
    }
}