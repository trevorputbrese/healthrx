package com.healthrx.web;

import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.healthrx.service.ChatService;
import com.healthrx.web.dto.ChatDtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The HealthRx Assistant chat endpoint (knowledge MCP tools through the gateway). */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService service;

    public ChatController(ChatService service) {
        this.service = service;
    }

    public record ChatRequest(
            UUID conversationId,
            @NotBlank @Size(max = 2000) String message) {
    }

    @PostMapping
    public ChatDtos.ChatResult send(@Valid @RequestBody ChatRequest body) {
        return service.send(body.conversationId(), body.message());
    }
}
