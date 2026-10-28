package com.teamer.teapot.ai.core.controller;

import com.teamer.teapot.ai.common.model.PageData;
import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.core.model.AgentDO;
import com.teamer.teapot.ai.core.model.dto.AgentCreateRequest;
import com.teamer.teapot.ai.core.model.dto.AgentUpdateRequest;
import com.teamer.teapot.ai.core.model.dto.BatchMoveWorkspaceRequest;
import com.teamer.teapot.ai.core.model.dto.BindSkillRequest;
import com.teamer.teapot.ai.core.model.dto.ChatDebugRequest;
import com.teamer.teapot.ai.core.model.dto.MoveWorkspaceRequest;
import com.teamer.teapot.ai.core.model.dto.SessionHistoryItem;
import com.teamer.teapot.ai.core.model.dto.SessionMessageItem;
import com.teamer.teapot.ai.core.model.dto.SkillEnabledRequest;
import com.teamer.teapot.ai.core.model.vo.AgentDetailVO;
import com.teamer.teapot.ai.core.model.vo.FeatureMiddlewareVO;
import com.teamer.teapot.ai.core.model.vo.MemoryStoreVO;
import com.teamer.teapot.ai.core.service.AgentService;
import com.teamer.teapot.ai.core.service.MemoryStoreService;
import com.teamer.teapot.ai.core.service.SessionHistoryService;
import com.teamer.teapot.ai.core.tool.ToolCatalog;
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
 * Agent 管理接口（SPEC §7；权限由 RbacAccessFilter 按 resource-list 控制）。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;
    private final SessionHistoryService sessionHistoryService;
    private final MemoryStoreService memoryStoreService;

    public AgentController(AgentService agentService, SessionHistoryService sessionHistoryService,
                           MemoryStoreService memoryStoreService) {
        this.agentService = agentService;
        this.sessionHistoryService = sessionHistoryService;
        this.memoryStoreService = memoryStoreService;
    }

    @GetMapping("/list")
    public Result<PageData<AgentDO>> list(@RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(defaultValue = "false") boolean includeDisabled,
                                          @RequestParam(required = false) Long workspaceId) {
        return Result.ok(agentService.list(page, size, keyword, includeDisabled, workspaceId));
    }

    @GetMapping("/detail/{agentKey}")
    public Result<AgentDetailVO> detail(@PathVariable String agentKey) {
        return Result.ok(agentService.detail(agentKey));
    }

    /**
     * feature 驱动中间件清单（SPEC §29.23）：schema 由中间件配置类内省产出，界面据此渲染配置表单；
     * 带 agentKey 时同时返回该 Agent 已存的配置与解析错误。
     */
    @GetMapping("/middleware-schema")
    public Result<List<FeatureMiddlewareVO>> middlewareSchema(@RequestParam(required = false) String agentKey) {
        return Result.ok(agentService.middlewareSchema(agentKey));
    }

    /**
     * 可分组工具目录（SPEC §31）：工具名 + 说明 + 来源，配置页据此把工具拖进分组。
     * 不依赖 agentKey —— 清单按依赖版本反射得出，与该 Agent 实际装配了哪些工具无关
     * （分组里写了没装配的名字，框架按名匹配不上就忽略）。
     */
    @GetMapping("/tool-catalog")
    public Result<List<ToolCatalog.Entry>> toolCatalog() {
        return Result.ok(ToolCatalog.entries());
    }

    @PostMapping("/create")
    public Result<AgentDO> create(@Valid @RequestBody AgentCreateRequest request) {
        return Result.ok(agentService.create(request));
    }

    @PutMapping("/update/{agentKey}")
    public Result<AgentDO> update(@PathVariable String agentKey,
                                  @Valid @RequestBody AgentUpdateRequest request) {
        return Result.ok(agentService.update(agentKey, request));
    }

    /** 删除（软删） */
    @DeleteMapping("/delete/{agentKey}")
    public Result<Void> delete(@PathVariable String agentKey) {
        agentService.delete(agentKey);
        return Result.ok();
    }

    /** 移动 Agent 到别的空间（SPEC §28.4 需求 5；body.workspaceId=null = 移回个人空间） */
    @PutMapping("/{agentKey}/workspace")
    public Result<AgentDO> moveWorkspace(@PathVariable String agentKey,
                                         @RequestBody MoveWorkspaceRequest request) {
        return Result.ok(agentService.moveWorkspace(agentKey, request.getWorkspaceId()));
    }

    /** 批量移动到同一空间（SPEC §35）：整批一个事务，任一条不通过即全批回滚 */
    @PutMapping("/batchMoveWorkspace")
    public Result<Integer> moveWorkspaceBatch(@Valid @RequestBody BatchMoveWorkspaceRequest request) {
        return Result.ok(agentService.moveWorkspaceBatch(request.getAgentKeys(), request.getWorkspaceId()));
    }

    @PostMapping("/bindSkill/{agentKey}")
    public Result<Void> bindSkill(@PathVariable String agentKey,
                                  @Valid @RequestBody BindSkillRequest request) {
        agentService.bindSkill(agentKey, request.getSkillName());
        return Result.ok();
    }

    @PostMapping("/unbindSkill/{agentKey}")
    public Result<Void> unbindSkill(@PathVariable String agentKey,
                                    @Valid @RequestBody BindSkillRequest request) {
        agentService.unbindSkill(agentKey, request.getSkillName());
        return Result.ok();
    }

    /** 启停一条已存在的绑定（SPEC §33）：与解绑不同，绑定关系保留 */
    @PostMapping("/skillEnabled/{agentKey}")
    public Result<Void> setSkillEnabled(@PathVariable String agentKey,
                                        @Valid @RequestBody SkillEnabledRequest request) {
        agentService.setSkillEnabled(agentKey, request.getSkillName(), request.getEnabled());
        return Result.ok();
    }

    /** 模型下拉同步调试对话（SPEC §7.1） */
    @PostMapping("/chat/{agentKey}")
    public Result<String> chat(@PathVariable String agentKey,
                               @Valid @RequestBody ChatDebugRequest request) {
        return Result.ok(agentService.chat(agentKey, request));
    }

    /**
     * Agent 全量会话历史列表（SPEC §24.9，仅 admin，Service 层 requireAdmin 兜底）：
     * Web + 渠道两索引 union，按活跃时间倒序分页。
     */
    @GetMapping("/{agentKey}/session-history")
    public Result<List<SessionHistoryItem>> sessionHistory(@PathVariable String agentKey,
                                                           @RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "20") int size,
                                                           @RequestParam(required = false) String keyword) {
        return Result.ok(sessionHistoryService.list(agentKey, page, size, keyword));
    }

    /** 会话全文回放（SPEC §24.9，仅 admin）：不校验会话归属，source 区分图片引用策略 */
    @GetMapping("/{agentKey}/session-history/{userId}/{sessionId}/messages")
    public Result<List<SessionMessageItem>> sessionHistoryMessages(@PathVariable String agentKey,
                                                                   @PathVariable String userId,
                                                                   @PathVariable String sessionId,
                                                                   @RequestParam(defaultValue = "web") String source) {
        return Result.ok(sessionHistoryService.messages(userId, sessionId, source));
    }

    /** 删除单条历史会话（SPEC §24.9，仅 admin）：stateStore 状态 + 对应索引表 */
    @DeleteMapping("/{agentKey}/session-history/{userId}/{sessionId}")
    public Result<Void> deleteSessionHistory(@PathVariable String agentKey,
                                             @PathVariable String userId,
                                             @PathVariable String sessionId,
                                             @RequestParam(defaultValue = "web") String source) {
        sessionHistoryService.delete(userId, sessionId, source);
        return Result.ok();
    }

    /**
     * Redis 记忆内容查询（SPEC §27 记忆管理）：按命名空间 uid 分组返回记忆文件清单（含正文）。
     */
    @GetMapping("/{agentKey}/memory-items")
    public Result<List<MemoryStoreVO.UserGroup>> memoryItems(@PathVariable String agentKey) {
        return Result.ok(memoryStoreService.listItems(agentKey));
    }

    /** Redis 记忆逐条删除（SPEC §27 记忆管理）：删除指定 uid 命名空间下的单个记忆文件 */
    @DeleteMapping("/{agentKey}/memory-item")
    public Result<Void> deleteMemoryItem(@PathVariable String agentKey,
                                         @RequestParam String uid,
                                         @RequestParam String path) {
        memoryStoreService.deleteItem(agentKey, uid, path);
        return Result.ok();
    }
}
