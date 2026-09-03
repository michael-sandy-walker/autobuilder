package com.example.library.domain.repository;

import com.example.library.domain.model.Member;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class MemberRepository {

    private final Map<Long, Member> store = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);

    public Member save(Member member) {
        if (member.getId() == null) {
            member.setId(idGenerator.incrementAndGet());
        }
        store.put(member.getId(), member);
        return member;
    }

    public Optional<Member> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    public Optional<Member> findByEmail(String email) {
        return store.values().stream()
                .filter(m -> email.equals(m.getEmail()))
                .findFirst();
    }

    public List<Member> findAll() {
        return List.copyOf(store.values());
    }
}