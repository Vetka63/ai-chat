package com.example.aichat.api;

import com.example.aichat.service.ChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.reply(request.message());
    }

    public record ChatRequest(
            @NotBlank(message = "Message must not be blank")
            @Size(max = 10_000, message = "Message must not exceed 10000 characters")
            String message
    ) {
    }

    public record ChatResponse(String reply, String source) {
    }
}

