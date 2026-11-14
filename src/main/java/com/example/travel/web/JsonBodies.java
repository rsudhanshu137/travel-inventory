package com.example.travel.web;

import com.example.travel.model.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Bodies arrive as raw text and are parsed here so that malformed JSON is a clean 400, and so the service can run
 * its own ordered checks (e.g. duplicate-before-deep-validation) instead of failing inside data binding.
 */
@Component
public class JsonBodies {
    private final ObjectMapper mapper;

    public JsonBodies(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode parse(String body) {
        if (body == null || body.isBlank()) throw ApiException.badRequest("request body is required");
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("request body is not valid JSON");
        }
    }
}
