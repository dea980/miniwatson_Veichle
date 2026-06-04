package com.miniwatson.controller;

import com.miniwatson.dto.AskRequest;
import com.miniwatson.service.RagService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService){
        this.ragService = ragService;
    }
    @PostMapping("/ask")
    public RagService.RagResult ask(@RequestBody AskRequest request) throws IOException {
        return ragService.ask(request.getQuestion());
    }
}
