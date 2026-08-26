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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Skill 工坊接口（SPEC §8/§15；权限由 RbacAccessFilter 按 resource-list 控制）。
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

    /** Git 来源状态（SPEC §15.9；登录可读，viewer 在 resource-list 显式放行） */
    @GetMapping("/git/status")
    public Result<Map<String, Object>> gitStatus() {
        return Result.ok(skillService.gitStatus());
    }

    /** Git 手动同步（SPEC §15.9；developer，被 /api/skill/* 通配覆盖） */
    @PostMapping("/git/sync")
    public Result<Map<String, Object>> gitSync() {
        return Result.ok(skillService.gitSync());
    }

    /** zip 导入（双落点）：target=oss 写 OSS 对象（同名覆盖）；target=mysql 存平台库（同名 upsert） */
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    public Result<Map<String, Object>> importSkill(@RequestParam("file") MultipartFile file,
                                                   @RequestParam(name = "target", defaultValue = "oss") String target) {
        return Result.ok(skillService.importSkill(file, target));
    }

    /** 任意 Git 仓库导入：临时 clone 后按 zip 导入同款规则入库（developer，被 /api/skill/* 通配覆盖） */
    @PostMapping("/import/git")
    public Result<Map<String, Object>> importFromGit(@RequestBody Map<String, String> body) {
        return Result.ok(skillService.importFromGit(
                body.get("url"), body.get("branch"), body.get("target")));
    }

    /** OSS 来源状态 */
    @GetMapping("/oss/status")
    public Result<Map<String, Object>> ossStatus() {
        return Result.ok(skillService.ossStatus());
    }

    /** OSS 来源手动刷新缓存 */
    @PostMapping("/oss/refresh")
    public Result<Map<String, Object>> ossRefresh() {
        return Result.ok(skillService.ossRefresh());
    }
}
