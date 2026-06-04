package com.miniwatson.controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

@RestController
public class HelloController {
    @GetMapping("/api/hello")
    public String hello() {
        return "hello watsonx";
    }

    @GetMapping("/api/version")
    public String version(){
        return "1.0";
    }
}

