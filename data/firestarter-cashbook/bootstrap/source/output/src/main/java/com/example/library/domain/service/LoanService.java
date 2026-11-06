package com.example.library.domain.service;

import com.example.library.domain.exception.ConflictException;
import com.example.library.domain.exception.NotFoundException;
import com.example.library.domain.exception.UnprocessableEntityException;
import com.example.library.domain.model.Book;
import com.example.library.domain.model.Loan;
import com.example.library.domain.model.Member;
import com.example.library.domain.repository.BookRepository;
import com.example.library.domain.repository.LoanRepository;
import com.example.library.domain.repository.MemberRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LoanService {

    private final BookRepository bookRepository;
    private final MemberRepository memberRepository;
    private final LoanRepository loanRepository;

    public LoanService(BookRepository bookRepository, MemberRepository memberRepository, LoanRepository loanRepository) {
        this.bookRepository = bookRepository;
        this.memberRepository = memberRepository;
        this.loanRepository = loanRepository;
    }

    public Loan lend(Long bookId, Long memberId) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new NotFoundException("Book not found with id: " + bookId));
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new NotFoundException("Member not found with id: " + memberId));

        if (loanRepository.hasActiveLoanForBook(bookId)) {
            throw new ConflictException("Book is already on loan");
        }

        long activeLoans = loanRepository.countActiveLoansForMember(memberId);
        if (activeLoans >= 3) {
            throw new UnprocessableEntityException("Member has reached the maximum of 3 active loans");
        }

        Loan loan = new Loan();
        loan.setBookId(bookId);
        loan.setMemberId(memberId);
        loan.setReturned(false);
        return loanRepository.save(loan);
    }

    public Loan returnLoan(Long loanId) {
        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new NotFoundException("Loan not found with id: " + loanId));

        if (loan.isReturned()) {
            throw new ConflictException("Loan is already returned");
        }

        loan.markReturned();
        return loan;
    }

    public List<Loan> findByMemberId(Long memberId) {
        return loanRepository.findByMemberId(memberId);
    }
}