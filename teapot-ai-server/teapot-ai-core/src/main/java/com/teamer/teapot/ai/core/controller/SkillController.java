package com.teamer.teapot.ai.core.controller;

import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.core.model.dto.SkillSaveRequest;
import com.teamer.teapot.ai.core.model.vo.SkillDetailVO;
import com.teamer.teapot.ai.core.model.vo.SkillListVO;
import com.teamer.teapot.ai.core.service.SkillService;
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
 * Skill 工坊接口（SPEC §8；权限由 RbacAccessFilter 按 resource-list 控制）。
 */
@RestController
@RequestMapping("/api/skill")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @GetMapping("/list")
    public Result<List<SkillListVO>> list() {
        return Result.ok(skillService.list());
    }

    @GetMapping("/detail/{name}")
    public Result<SkillDetailVO> detail(@PathVariable String name) {
        return Result.ok(skillService.detail(name));
    }

    /** 新建/更新（upsert） */
    @PostMapping("/save")
    public Result<Void> save(@Valid @RequestBody SkillSaveRequest request) {
        skillService.save(request);
        return Result.ok();
    }

    /** 删除（级联资源 + 解绑所有 Agent） */
    @DeleteMapping("/delete/{name}")
    public Result<Void> delete(@PathVariable String name) {
        skillService.delete(name);
        return Result.ok();
    }

    /** 预览生成的 SKILL.md（不落库） */
    @PostMapping("/preview")
    public Result<String> preview(@Valid @RequestBody SkillSaveRequest request) {
        return Result.ok(skillService.preview(request));
    }
}
