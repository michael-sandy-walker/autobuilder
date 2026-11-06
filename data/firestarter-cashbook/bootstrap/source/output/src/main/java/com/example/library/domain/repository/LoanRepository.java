package com.example.library.domain.repository;

import com.example.library.domain.model.Loan;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class LoanRepository {

    private final List<Loan> loans = new ArrayList<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    public Loan save(Loan loan) {
        if (loan.getId() == null) {
            loan.setId(idGenerator.incrementAndGet());
            loans.add(loan);
        }
        return loan;
    }

    public Optional<Loan> findById(Long id) {
        return loans.stream()
                .filter(loan -> loan.getId().equals(id))
                .findFirst();
    }

    public List<Loan> findByMemberId(Long memberId) {
        return loans.stream()
                .filter(loan -> loan.getMemberId().equals(memberId))
                .toList();
    }

    public boolean hasActiveLoanForBook(Long bookId) {
        return loans.stream()
                .anyMatch(loan -> loan.getBookId().equals(bookId) && !loan.isReturned());
    }

    public long countActiveLoansForMember(Long memberId) {
        return loans.stream()
                .filter(loan -> loan.getMemberId().equals(memberId) && !loan.isReturned())
                .count();
    }
}