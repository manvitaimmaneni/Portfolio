// java
package com.example.Api_Assets.controller;

import com.example.Api_Assets.dto.ChatRequest;
import com.example.Api_Assets.dto.ChatResponse;
import com.example.Api_Assets.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    @Autowired
    private ChatService chatService;

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest req) {
        String reply = chatService.processMessage(req.getMessage());
        ChatResponse res = new ChatResponse();
        res.setReply(reply);
        return res;
    }
}

