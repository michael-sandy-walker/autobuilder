package com.example.library.domain.repository;

import com.example.library.domain.model.Member;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class MemberRepository {
    private final Map<Long, Member> store = new LinkedHashMap<>();
    private final Map<String, Long> emailIndex = new HashMap<>();
    private final AtomicLong idGen = new AtomicLong(0);

    public Member save(Member member) {
        if (member.getId() == null) {
            member.setId(idGen.incrementAndGet());
        }
        store.put(member.getId(), member);
        emailIndex.put(member.getEmail().toLowerCase(), member.getId());
        return member;
    }

    public Optional<Member> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    public Optional<Member> findByEmail(String email) {
        Long id = emailIndex.get(email.toLowerCase());
        return id != null ? Optional.ofNullable(store.get(id)) : Optional.empty();
    }

    public boolean emailExists(String email) {
        return emailIndex.containsKey(email.toLowerCase());
    }
}