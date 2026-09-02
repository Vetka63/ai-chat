package com.example.aichat.api;

import com.example.aichat.service.ChatService;
import com.example.aichat.enums.HistoryRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.reply(request);
    }

    public record ChatRequest(
            @NotBlank(message = "Message must not be blank")
            @Size(max = 100_000, message = "Message must not exceed 100000 characters")
            String message,

            @Pattern(
                    regexp = "^[a-z][a-z0-9-]{1,63}$",
                    message = "Profile id has an invalid format"
            )
            String profileId,

            @Pattern(
                    regexp = "^[a-z][a-z0-9-]{1,31}$",
                    message = "Response mode has an invalid format"
            )
            String responseMode,

            @Size(max = 200, message = "History must not exceed 200 messages")
            List<@Valid HistoryMessage> history
    ) {
        public ChatRequest {
            history = history == null ? List.of() : List.copyOf(history);
        }
    }

    public record HistoryMessage(
            @NotNull(message = "History role is required")
            HistoryRole role,

            @NotBlank(message = "History message must not be blank")
            @Size(max = 100_000, message = "History message is too long")
            String content
    ) {
    }

    public record ChatResponse(
            String reply,
            Object structuredReply,
            String source,
            String profileId,
            String responseMode,
            ChatMeta meta
    ) {
    }

    public record ChatMeta(
            String model,
            String finishReason,
            Integer maxTokens,
            String responseFormat,
            Object stop
    ) {
    }
}
