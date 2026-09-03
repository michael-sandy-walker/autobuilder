package com.example.library.domain.model;

public class Loan {
    private Long id;
    private Long bookId;
    private Long memberId;
    private boolean returned;

    public Loan() {
    }

    public Loan(Long id, Long bookId, Long memberId, boolean returned) {
        this.id = id;
        this.bookId = bookId;
        this.memberId = memberId;
        this.returned = returned;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Loan loan = (Loan) o;
        return java.util.Objects.equals(id, loan.id);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Loan{id=" + id + ", bookId=" + bookId + ", memberId=" + memberId + ", returned=" + returned + "}";
    }
}