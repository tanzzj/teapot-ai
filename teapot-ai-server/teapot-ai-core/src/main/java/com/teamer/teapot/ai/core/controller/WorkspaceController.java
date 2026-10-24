package com.teamer.teapot.ai.core.controller;

import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.core.model.WorkspaceDO;
import com.teamer.teapot.ai.core.model.dto.WorkspaceCreateRequest;
import com.teamer.teapot.ai.core.model.dto.WorkspaceMemberRequest;
import com.teamer.teapot.ai.core.model.vo.WorkspaceMemberVO;
import com.teamer.teapot.ai.core.model.vo.WorkspaceVO;
import com.teamer.teapot.ai.core.service.WorkspaceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 业务空间接口（SPEC §28.4）。跨空间数据隔离由 WorkspaceService 的 requireMember 兜底
 * （RbacAccessFilter 只按 role→URI 粗粒度放行，见 application.yml resource-list）。
 */
@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /** 当前用户可见空间（顶栏下拉数据源，SPEC §28.3） */
    @GetMapping("/list")
    public Result<List<WorkspaceVO>> list() {
        return Result.ok(workspaceService.listVisible());
    }

    /** 全部启用团队空间（仅 admin，模型开放范围配置数据源，SPEC §28.11） */
    @GetMapping("/all")
    public Result<List<WorkspaceVO>> all() {
        return Result.ok(workspaceService.listAllEnabledForAdmin());
    }

    /** 新建团队空间（必填校验 + 批量加成员，SPEC §28.4） */
    @PostMapping("/create")
    public Result<WorkspaceDO> create(@Valid @RequestBody WorkspaceCreateRequest request) {
        return Result.ok(workspaceService.create(request));
    }

    /** 空间成员列表（跨空间隔离：非成员 403，SPEC §28.3） */
    @GetMapping("/{id}/members")
    public Result<List<WorkspaceMemberVO>> members(@PathVariable Long id) {
        return Result.ok(workspaceService.listMembers(id));
    }

    /** 加成员（加成员即授权，SPEC §28.4） */
    @PostMapping("/{id}/member")
    public Result<Void> addMember(@PathVariable Long id, @Valid @RequestBody WorkspaceMemberRequest request) {
        workspaceService.addMember(id, request.getUserId());
        return Result.ok();
    }

    /** 移成员（仅 owner/白名单，SPEC §28.4） */
    @DeleteMapping("/{id}/member/{userId}")
    public Result<Void> removeMember(@PathVariable Long id, @PathVariable String userId) {
        workspaceService.removeMember(id, userId);
        return Result.ok();
    }

    /** 停用空间（仅 owner/白名单，SPEC §28.4） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        workspaceService.delete(id);
        return Result.ok();
    }
}
