package com.example.library.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class MemberControllerTest {

    private MockMvc mockMvc;
    private MemberService memberService;
    private MemberController memberController;

    @BeforeEach
    public void setUp() {
        memberService = mock(MemberService.class);
        memberController = new MemberController(memberService);
        mockMvc = MockMvcBuilders.standaloneSetup(memberController).build();
    }

    @Test
    public void testCreateMember_Success() throws Exception {
        Member member = new Member("John Doe", "john@example.com");
        member.setId(1L);
        when(memberService.save(any(Member.class))).thenReturn(member);

        mockMvc.perform(post("/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"John Doe\",\"email\":\"john@example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("John Doe"))
                .andExpect(jsonPath("$.email").value("john@example.com"));
    }

    @Test
    public void testCreateMember_ValidationException() throws Exception {
        when(memberService.save(any(Member.class))).thenThrow(new ValidationException("Name is required", 400));

        mockMvc.perform(post("/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"john@example.com\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void testCreateMember_DuplicateEmail() throws Exception {
        when(memberService.save(any(Member.class))).thenThrow(new ValidationException("Member with email john@example.com already exists", 409));

        mockMvc.perform(post("/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"John Doe\",\"email\":\"john@example.com\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    public void testGetMemberById_Success() throws Exception {
        Member member = new Member("John Doe", "john@example.com");
        member.setId(1L);
        when(memberService.getMemberById(1L)).thenReturn(java.util.Optional.of(member));

        mockMvc.perform(get("/members/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("John Doe"))
                .andExpect(jsonPath("$.email").value("john@example.com"));
    }

    @Test
    public void testGetMemberById_NotFound() throws Exception {
        when(memberService.getMemberById(1L)).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/members/1"))
                .andExpect(status().isNotFound());
    }
}