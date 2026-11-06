package com.example.library.domain.model;

public class Loan {

    private Long id;
    private Long bookId;
    private Long memberId;
    private boolean returned;

    public Loan() {
    }

    public Loan(Long bookId, Long memberId) {
        this.bookId = bookId;
        this.memberId = memberId;
        this.returned = false;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }

    public boolean isReturned() {
        return returned;
    }

    public void setReturned(boolean returned) {
        this.returned = returned;
    }

    public void markReturned() {
        this.returned = true;
    }
}