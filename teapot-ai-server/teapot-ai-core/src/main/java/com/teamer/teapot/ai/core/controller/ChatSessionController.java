package com.teamer.teapot.ai.core.controller;

import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.core.model.ChatSessionDO;
import com.teamer.teapot.ai.core.model.dto.SessionCreateRequest;
import com.teamer.teapot.ai.core.model.dto.SessionRenameRequest;
import com.teamer.teapot.ai.core.service.ChatSessionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话索引接口（SPEC §9）：仅管理会话列表/标题，
 * 消息体走 AG-UI 链路（agentscope_sessions 为唯一事实源）。
 */
@RestController
@RequestMapping("/api/chat/session")
public class ChatSessionController {

    private final ChatSessionService chatSessionService;

    public ChatSessionController(ChatSessionService chatSessionService) {
        this.chatSessionService = chatSessionService;
    }

    @GetMapping("/list")
    public Result<List<ChatSessionDO>> list(@RequestParam(required = false) String agentKey) {
        return Result.ok(chatSessionService.list(agentKey));
    }

    @PostMapping("/create")
    public Result<ChatSessionDO> create(@Valid @RequestBody SessionCreateRequest request) {
        return Result.ok(chatSessionService.create(request));
    }

    /** 会话改名（懒创建会话后以首条消息回填标题） */
    @PutMapping("/rename")
    public Result<Void> rename(@Valid @RequestBody SessionRenameRequest request) {
        chatSessionService.rename(request);
        return Result.ok();
    }

    /** 清空会话（删状态 + 删索引） */
    @DeleteMapping("/clear/{sessionId}")
    public Result<Void> clear(@PathVariable String sessionId) {
        chatSessionService.clear(sessionId);
        return Result.ok();
    }
}
