package com.example.library.domain.controller;

import com.example.library.domain.model.Loan;
import com.example.library.domain.service.LoanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/loans")
public class LoanController {

    private final LoanService loanService;

    public LoanController(LoanService loanService) {
        this.loanService = loanService;
    }

    @PostMapping
    public ResponseEntity<Loan> lend(@RequestBody(required = false) Map<String, Long> body) {
        if (body == null || !body.containsKey("bookId") || !body.containsKey("memberId")) {
            throw new com.example.library.domain.exception.ValidationException("bookId and memberId are required");
        }
        Loan loan = loanService.lend(body.get("bookId"), body.get("memberId"));
        return ResponseEntity.status(201).body(loan);
    }

    @PostMapping("/{id}/return")
    public ResponseEntity<Loan> returnLoan(@PathVariable Long id) {
        Loan loan = loanService.returnLoan(id);
        return ResponseEntity.ok(loan);
    }

    @GetMapping
    public ResponseEntity<List<Loan>> listByMember(@RequestParam Long memberId) {
        List<Loan> loans = loanService.findByMemberId(memberId);
        return ResponseEntity.ok(loans);
    }
}