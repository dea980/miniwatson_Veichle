package com.miniwatson.controller;

import com.miniwatson.service.GraphService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/graph")
public class GraphController {
    private final GraphService graph;
    public GraphController(GraphService graph) { this.graph = graph; }

    @GetMapping("/model-components")
    public Map<String, Object> modelComponents(@RequestParam String model) {
        return Map.of("model", model, "components", graph.modelComponents(model));
    }

    @GetMapping("/component-profile")
    public Map<String, Object> componentProfile(@RequestParam String model, @RequestParam String component) {
        return graph.componentProfile(model, component);
    }
}