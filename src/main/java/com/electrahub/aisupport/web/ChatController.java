package com.electrahub.aisupport.web;

import com.electrahub.aisupport.model.ChatDtos.SendMessageRequest;
import com.electrahub.aisupport.model.ChatDtos.SendMessageResponse;
import com.electrahub.aisupport.service.ChatThreadStore;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
class ChatController {
    private final ChatThreadStore threadStore;

    ChatController(ChatThreadStore threadStore) {
        this.threadStore = threadStore;
    }

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    SendMessageResponse sendMessage(@Valid @RequestBody SendMessageRequest request) {
        var pending = threadStore.create(request.threadId(), request.content(), request.context());
        return new SendMessageResponse(pending.threadId(), pending.messageId());
    }
}
