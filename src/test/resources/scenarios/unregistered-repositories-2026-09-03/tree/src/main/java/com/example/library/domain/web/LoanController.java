package com.example.library.domain.web;

import com.example.library.domain.model.Book;
import com.example.library.domain.model.Loan;
import com.example.library.domain.model.Member;
import com.example.library.domain.repository.BookRepository;
import com.example.library.domain.repository.LoanRepository;
import com.example.library.domain.repository.MemberRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/loans")
public class LoanController {

    private final BookRepository bookRepository;
    private final MemberRepository memberRepository;
    private final LoanRepository loanRepository;

    public LoanController(BookRepository bookRepository,
                          MemberRepository memberRepository,
                          LoanRepository loanRepository) {
        this.bookRepository = bookRepository;
        this.memberRepository = memberRepository;
        this.loanRepository = loanRepository;
    }

    @PostMapping
    public ResponseEntity<?> createLoan(@RequestBody Map<String, Object> body) {
        Object bookIdObj = body.get("bookId");
        Object memberIdObj = body.get("memberId");

        if (bookIdObj == null || memberIdObj == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", "bookId and memberId are required"));
        }

        Long bookId;
        Long memberId;
        try {
            bookId = Long.valueOf(bookIdObj.toString());
            memberId = Long.valueOf(memberIdObj.toString());
        } catch (NumberFormatException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", "bookId and memberId must be numeric"));
        }

        Book book = bookRepository.findById(bookId).orElse(null);
        if (book == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Book not found"));
        }

        Member member = memberRepository.findById(memberId).orElse(null);
        if (member == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Member not found"));
        }

        List<Loan> activeLoansForBook = loanRepository.findActiveByBookId(bookId);
        if (!activeLoansForBook.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "Book is already on loan"));
        }

        List<Loan> activeLoansForMember = loanRepository.findActiveByMemberId(memberId);
        if (activeLoansForMember.size() >= 3) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("message", "Member already holds 3 active loans"));
        }

        Loan loan = new Loan();
        loan.setBookId(bookId);
        loan.setMemberId(memberId);
        loan.setReturned(false);
        Loan saved = loanRepository.save(loan);

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }
}