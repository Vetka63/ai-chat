package com.example.aichat.task.chat.controller;

import com.example.aichat.task.chat.service.ChatService;
import com.example.aichat.task.chat.controller.dto.ChatMeta;
import com.example.aichat.task.chat.controller.dto.ChatRequest;
import com.example.aichat.task.chat.controller.dto.ChatResponse;
import com.example.aichat.task.chat.model.ChatCommand;
import com.example.aichat.common.inputpolicy.model.InputHistoryMessage;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Принимает, проверяет и преобразует HTTP-запросы на границе ChatController. */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        var result = chatService.reply(new ChatCommand(
                request.message(),
                request.profileId(),
                request.responseMode(),
                request.history().stream()
                        .map(item -> new InputHistoryMessage(item.role(), item.content()))
                        .toList()
        ));
        return new ChatResponse(
                result.reply(),
                result.structuredReply(),
                result.source(),
                result.profileId(),
                result.responseMode(),
                new ChatMeta(
                        result.meta().model(),
                        result.meta().finishReason(),
                        result.meta().maxTokens(),
                        result.meta().responseFormat(),
                        result.meta().stop()
                )
        );
    }
}
