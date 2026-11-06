package com.example.library.domain.controller;

import com.example.library.domain.exception.NotFoundException;
import com.example.library.domain.model.Member;
import com.example.library.domain.service.MemberService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @PostMapping
    public ResponseEntity<Member> create(@RequestBody Map<String, String> body) {
        Member member = memberService.create(body.get("name"), body.get("email"));
        return ResponseEntity.status(201).body(member);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Member> get(@PathVariable Long id) {
        return memberService.findById(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NotFoundException("member not found: " + id));
    }
}