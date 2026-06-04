package com.miniwatson.controller;
import com.miniwatson.dto.AskRequest;
//role
// HTTP 요청 받음
// 데이터 검증 (입력값이 valid한지)
// Service에게 일 시킴
// 응답 보냄

import com.miniwatson.service.OllamaService;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;

@RestController
@RequestMapping("/api")
public class AskController {
    private final OllamaService ollamaService;
    // 사용자가 POST /api

    // 생성자
    public AskController(OllamaService ollamaService){
        this.ollamaService = ollamaService;
    }

    @PostMapping("/ask")
    public String ask(@RequestBody AskRequest request){
        return ollamaService.ask(request.getQuestion());
    }

}


//Q. 만약 본인이 AdminController를 만든다면?
// Request Mapping("/admin")
// public class AdminController{
//      @GetMapping("/users")
//관리자 기능 모음 컨트롤러. URL: /admin/users, /admin/logs, /admin/stats.
//본인이라면 @RequestMapping 어떻게 쓸까?