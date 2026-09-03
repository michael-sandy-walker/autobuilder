package com.example.library.domain.repository;

import com.example.library.domain.model.Loan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public class LoanRepository {

    private final List<Loan> loans = new ArrayList<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    public Loan save(Loan loan) {
        if (loan.getId() == null) {
            loan.setId(idGenerator.incrementAndGet());
        }
        loans.add(loan);
        return loan;
    }

    public Optional<Loan> findById(Long id) {
        return loans.stream().filter(l -> l.getId().equals(id)).findFirst();
    }

    public List<Loan> findByMemberId(Long memberId) {
        return loans.stream().filter(l -> l.getMemberId().equals(memberId)).toList();
    }

    public List<Loan> findActiveByBookId(Long bookId) {
        return loans.stream().filter(l -> l.getBookId().equals(bookId) && !l.isReturned()).toList();
    }

    public List<Loan> findActiveByMemberId(Long memberId) {
        return loans.stream().filter(l -> l.getMemberId().equals(memberId) && !l.isReturned()).toList();
    }
}