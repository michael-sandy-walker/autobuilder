package com.example.library.domain.model;

public class Book {
    private Long id;
    private String title;
    private String isbn;

    public Book() {}

    public Book(Long id, String title, String isbn) {
        this.id = id;
        this.title = title;
        this.isbn = isbn;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getIsbn() { return isbn; }
    public void setIsbn(String isbn) { this.isbn = isbn; }
}