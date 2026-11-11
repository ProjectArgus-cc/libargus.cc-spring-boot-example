package cc.projectargus.workbench.web;

import cc.projectargus.workbench.chat.ChatService;
import cc.projectargus.workbench.web.dto.ChatRequestDto;
import cc.projectargus.workbench.web.dto.ChatResponseDto;
import cc.projectargus.workbench.web.dto.StopRequestDto;
import cc.projectargus.workbench.web.dto.StopResponseDto;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST and Server-Sent Events (SSE) endpoints for synchronous chat, streaming generation,
 * and cooperative cancellation.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = Objects.requireNonNull(chatService, "chatService");
    }

    @PostMapping(
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ChatResponseDto chat(@RequestBody(required = false) ChatRequestDto requestDto) {
        return chatService.chat(requestDto);
    }

    @PostMapping(
        path = "/stream",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter streamChat(@RequestBody(required = false) ChatRequestDto requestDto) {
        return chatService.streamChat(requestDto);
    }

    @PostMapping(
        path = "/stop",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public StopResponseDto stopChat(@RequestBody(required = false) StopRequestDto stopRequest) {
        String requestId = stopRequest != null ? stopRequest.requestId() : null;
        return chatService.stopGeneration(requestId);
    }
}
