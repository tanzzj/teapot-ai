package com.teamer.teapot.ai.core.controller;

import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.core.tool.MediaModelCatalog;
import com.teamer.teapot.ai.core.model.ModelEntryDO;
import com.teamer.teapot.ai.core.service.ModelService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型入口（SPEC §6.4 修订：界面配置化）。
 * presets 供 Agent 下拉（developer/viewer 可读）；CRUD 仅 admin（service 层校验）。
 */
@RestController
@RequestMapping("/api/model")
public class ModelController {

    private final ModelService modelService;

    public ModelController(ModelService modelService) {
        this.modelService = modelService;
    }

    /** Agent 模型下拉枚举（provider:model 列表，DB 入口优先，yml 兜底） */
    @GetMapping("/presets")
    public Result<List<String>> presets() {
        return Result.ok(modelService.listEnabledModelIds());
    }

    /** 启用入口的能力位（SPEC §19：前端多模态 gating，任意登录用户可读） */
    @GetMapping("/capabilities")
    public Result<List<ModelEntryDO>> capabilities() {
        return Result.ok(modelService.listEnabledCapabilities());
    }

    /** DashScope 在售模型清单（admin；新建/编辑入口的模型名下拉数据源） */
    @GetMapping("/vendor-models/{provider}")
    public Result<List<String>> vendorModels(@PathVariable String provider) {
        if (!"dashscope".equals(provider)) {
            return Result.ok(List.of());
        }
        return Result.ok(modelService.listDashScopeVendorModels());
    }

    /**
     * 生成模型目录（SPEC-media-gen §4.8，任意登录用户可读）：
     * 按能力位给出可选生成模型与默认值，供 Agent 配置页的生图/生视频模型下拉。
     * 不从 vendor-models 派生：媒体模型不在那个清单里，且分散在四个 endpoint（见 MediaModelCatalog 注释）。
     */
    @GetMapping("/media-models")
    public Result<List<Map<String, Object>>> mediaModels() {
        List<Map<String, Object>> catalog = MediaModelCatalog.entries().stream()
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("field", entry.field());
                    item.put("label", entry.label());
                    item.put("tool", entry.tool());
                    item.put("defaultModel", entry.defaultModel());
                    item.put("models", entry.models());
                    return item;
                })
                .toList();
        return Result.ok(catalog);
    }

    /** 全部模型入口（admin，含停用） */
    @GetMapping("/list")
    public Result<List<ModelEntryDO>> list() {
        return Result.ok(modelService.listAll());
    }

    @PostMapping("/create")
    public Result<ModelEntryDO> create(@RequestBody ModelEntryDO request) {
        return Result.ok(modelService.create(request));
    }

    @PutMapping("/{id}")
    public Result<ModelEntryDO> update(@PathVariable Long id, @RequestBody ModelEntryDO request) {
        return Result.ok(modelService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        modelService.delete(id);
        return Result.ok();
    }
}
