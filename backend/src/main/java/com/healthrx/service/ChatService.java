package com.healthrx.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.healthrx.web.ApiException;
import com.healthrx.web.dto.ChatDtos;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;

/**
 * The HealthRx Assistant: a human-driven MCP client behind a chat box. Each message runs one
 * ChatClient tool-calling loop against the chat-assistant marketplace LLM with the knowledge MCP
 * server's tools mounted through the MCP gateway — the same governed path the agents use, so
 * every lookup is an audited gateway tool call (phase-3-design.md §3). Postgres MCP tools are a
 * planned follow-on, deliberately not mounted yet.
 *
 * <p>Conversations are in-memory (single API instance; demo-grade): capped per-conversation
 * history, idle conversations swept. The gateway MCP client connects lazily on the first
 * message, so local runs and test slices never need a live gateway.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String USER_AGENT = "healthrx-chat-assistant";
    private static final int MAX_HISTORY_MESSAGES = 16;
    private static final Duration CONVERSATION_TTL = Duration.ofHours(2);

    private static final String SYSTEM = """
            You are the HealthRx Assistant, a chat helper for care-team staff inside HealthRx,
            a specialty pharmacy care-operations demo system.

            Ground every medication or disease-state answer in the knowledge tools:
            - get_medication_guidance(medicationName) for one drug's guidance
            - get_condition_guidance(diseaseState) for a disease state's adherence/outreach context

            The formulary is FICTIONAL demo content: Oncora, Velmacin, Tarvexa (Oncology);
            Immunza, Rheumavy, Jakvoren (Rheumatology); Neurosphere, Mylenta-S, Releva
            (Multiple sclerosis); Gastronib, Colirex, Entovia (Gastroenterology). If asked about
            anything outside it (including real-world drugs), say the HealthRx formulary does not
            include it and offer what is available. Never state clinical guidance the tools did
            not return. Answer in one or two short paragraphs, plain prose, for care-team staff —
            this is demo reference material, not medical advice for patients.
            """;

    private record Conversation(List<Message> messages, Instant touchedAt) {
    }

    private final ChatClient chat;
    private final String gatewayUrl;
    private final String knowledgeEndpoint;
    private final Map<UUID, Conversation> conversations = new ConcurrentHashMap<>();
    private volatile McpSyncClient knowledge;

    public ChatService(ChatModel chatModel,
            @Value("${healthrx.chat.gateway-url}") String gatewayUrl,
            @Value("${healthrx.chat.knowledge-endpoint}") String knowledgeEndpoint) {
        this.chat = ChatClient.builder(chatModel).build();
        this.gatewayUrl = gatewayUrl;
        this.knowledgeEndpoint = knowledgeEndpoint;
    }

    public ChatDtos.ChatResult send(UUID conversationId, String message) {
        UUID id = conversationId != null ? conversationId : UUID.randomUUID();
        sweepIdleConversations();
        List<Message> history = conversations.containsKey(id)
                ? new ArrayList<>(conversations.get(id).messages()) : new ArrayList<>();

        List<ChatDtos.ToolCall> toolCalls = new ArrayList<>();
        List<ToolCallback> tools = recordingTools(toolCalls);

        long started = System.currentTimeMillis();
        String reply;
        try {
            ChatResponse response = chat.prompt()
                    .system(SYSTEM)
                    .messages(history)
                    .user(message)
                    .toolCallbacks(tools)
                    .call()
                    .chatResponse();
            reply = response.getResult() != null && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText() : null;
            var usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
            log.info("chat_message conversation={} tool_calls={} latency_ms={} tokens={}",
                    id, toolCalls.size(), System.currentTimeMillis() - started,
                    usage != null ? usage.getTotalTokens() : -1);
        } catch (Exception e) {
            log.warn("chat_message failed conversation={} — LLM or gateway unreachable", id, e);
            throw ApiException.upstreamUnavailable("ASSISTANT_UNAVAILABLE",
                    "The assistant could not reach its model or the MCP gateway. Try again shortly.",
                    Map.of());
        }
        if (reply == null || reply.isBlank()) {
            reply = "I could not produce an answer for that — try rephrasing the question.";
        }

        history.add(new UserMessage(message));
        history.add(new AssistantMessage(reply));
        while (history.size() > MAX_HISTORY_MESSAGES) {
            history.remove(0);
        }
        conversations.put(id, new Conversation(List.copyOf(history), Instant.now()));

        return new ChatDtos.ChatResult(id, reply, List.copyOf(toolCalls));
    }

    /** Knowledge MCP tools via the gateway, each call captured for the response's tool trace. */
    private List<ToolCallback> recordingTools(List<ChatDtos.ToolCall> sink) {
        ToolCallback[] raw = new SyncMcpToolCallbackProvider(List.of(knowledgeClient())).getToolCallbacks();
        return Arrays.stream(raw)
                .map(tc -> (ToolCallback) new RecordingToolCallback(tc, sink))
                .toList();
    }

    /**
     * Lazily connects the gateway MCP client on first use (double-checked; McpSyncClient is
     * thread-safe once initialized). A dead gateway therefore fails the chat request, never
     * application startup.
     */
    private McpSyncClient knowledgeClient() {
        McpSyncClient client = knowledge;
        if (client != null) {
            return client;
        }
        synchronized (this) {
            if (knowledge == null) {
                var transport = HttpClientStreamableHttpTransport.builder(gatewayUrl)
                        .endpoint(knowledgeEndpoint)
                        .customizeRequest(request -> request
                                .header("X-Agent-Id", "chat-assistant")
                                .header("User-Agent", USER_AGENT))
                        .build();
                knowledge = McpClient.sync(transport)
                        .requestTimeout(Duration.ofSeconds(30))
                        .initializationTimeout(Duration.ofSeconds(15))
                        .build();
            }
            return knowledge;
        }
    }

    private void sweepIdleConversations() {
        Instant cutoff = Instant.now().minus(CONVERSATION_TTL);
        conversations.entrySet().removeIf(e -> e.getValue().touchedAt().isBefore(cutoff));
    }

    private record RecordingToolCallback(ToolCallback delegate, List<ChatDtos.ToolCall> sink)
            implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            String result = delegate.call(toolInput);
            sink.add(new ChatDtos.ToolCall(delegate.getToolDefinition().name(), toolInput));
            return result;
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            String result = delegate.call(toolInput, toolContext);
            sink.add(new ChatDtos.ToolCall(delegate.getToolDefinition().name(), toolInput));
            return result;
        }
    }
}
