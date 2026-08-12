package com.teamer.teapot.ai.core.controller;

import com.teamer.teapot.ai.common.model.PageData;
import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.core.model.AgentDO;
import com.teamer.teapot.ai.core.model.dto.AgentCreateRequest;
import com.teamer.teapot.ai.core.model.dto.AgentUpdateRequest;
import com.teamer.teapot.ai.core.model.dto.BindSkillRequest;
import com.teamer.teapot.ai.core.model.dto.ChatDebugRequest;
import com.teamer.teapot.ai.core.model.vo.AgentDetailVO;
import com.teamer.teapot.ai.core.service.AgentService;
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

/**
 * Agent 管理接口（SPEC §7；权限由 RbacAccessFilter 按 resource-list 控制）。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping("/list")
    public Result<PageData<AgentDO>> list(@RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return Result.ok(agentService.list(page, size, keyword, includeDisabled));
    }

    @GetMapping("/detail/{agentKey}")
    public Result<AgentDetailVO> detail(@PathVariable String agentKey) {
        return Result.ok(agentService.detail(agentKey));
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

    /** 模型下拉同步调试对话（SPEC §7.1） */
    @PostMapping("/chat/{agentKey}")
    public Result<String> chat(@PathVariable String agentKey,
                               @Valid @RequestBody ChatDebugRequest request) {
        return Result.ok(agentService.chat(agentKey, request));
    }
}
