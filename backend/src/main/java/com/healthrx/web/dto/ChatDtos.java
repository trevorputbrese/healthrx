package com.healthrx.web.dto;

import java.util.List;
import java.util.UUID;

/** Chat assistant request/response shapes. */
public final class ChatDtos {

    private ChatDtos() {
    }

    /** One audited gateway tool call the assistant made while answering. */
    public record ToolCall(String tool, String arguments) {
    }

    /** POST /api/chat response: the reply plus the tool calls that grounded it. */
    public record ChatResult(UUID conversationId, String reply, List<ToolCall> toolCalls) {
    }
}
