package com.example.library.domain.service;

import com.example.library.domain.model.Member;
import com.example.library.domain.repository.MemberRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import com.example.library.domain.exception.DuplicateEmailException;
import com.example.library.domain.exception.ValidationException;

@Service
public class MemberService {
    private final MemberRepository repo;

    public MemberService(MemberRepository repo) {
        this.repo = repo;
    }

    public Member create(String name, String email) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("name is required");
        }
        if (email == null || email.isBlank()) {
            throw new ValidationException("email is required");
        }
        if (repo.emailExists(email)) {
            throw new DuplicateEmailException(email);
        }
        return repo.save(new Member(null, name, email));
    }

    public Optional<Member> findById(Long id) {
        return repo.findById(id);
    }
}