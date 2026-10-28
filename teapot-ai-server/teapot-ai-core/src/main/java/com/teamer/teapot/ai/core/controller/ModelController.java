package com.teamer.teapot.ai.core.controller;

import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.core.tool.MediaModelCatalog;
import com.teamer.teapot.ai.core.model.ModelEntryDO;
import com.teamer.teapot.ai.core.service.ModelService;
import com.teamer.teapot.ai.core.service.ModelVendorClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

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

    /** Agent 模型下拉枚举（provider:model 列表，DB 入口优先，yml 兜底）；workspaceId 非空则按空间开放范围过滤（SPEC §28.11） */
    @GetMapping("/presets")
    public Result<List<String>> presets(@RequestParam(required = false) Long workspaceId) {
        return Result.ok(modelService.listEnabledModelIds(workspaceId));
    }

    /**
     * 已接入的供应商及其思考档表达力（SPEC §29.16）：界面 provider 下拉与「默认/开启/关闭」
     * 禁用联动的数据源，与后端保存校验同源（策略 Bean 清单）。
     * {@code modelDiscovery} 表示该供应商能否自己报出上下文窗口与输入模态（SPEC §30.6）——
     * 为 true 时界面不再让人手填这两项。
     */
    @GetMapping("/providers")
    public Result<List<Map<String, Object>>> providers() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String provider : new TreeSet<>(modelService.providers())) {
            list.add(Map.of("provider", provider,
                    "thinkingSupport", modelService.thinkingSupportOf(provider).name(),
                    "modelDiscovery", modelService.supportsDiscovery(provider)));
        }
        return Result.ok(list);
    }

    /** 启用入口的能力位（SPEC §19：前端多模态 gating，任意登录用户可读） */
    @GetMapping("/capabilities")
    public Result<List<ModelEntryDO>> capabilities() {
        return Result.ok(modelService.listEnabledCapabilities());
    }

    /** 供应商在售模型清单（admin；新建/编辑入口的模型名下拉数据源，SPEC §30.2） */
    @GetMapping("/vendor-models/{provider}")
    public Result<List<String>> vendorModels(@PathVariable String provider) {
        return Result.ok(modelService.listVendorModels(provider));
    }

    /**
     * 模型入口探活（admin，SPEC §30.2）：一次 GET 验密钥与模型是否存在，
     * 顺带把供应商报出的上下文窗口回填进入口（0 = 该接口不给出窗口，仅报可用性）。
     */
    @PostMapping("/{id}/probe")
    public Result<ModelVendorClient.ProbeResult> probe(@PathVariable Long id) {
        return Result.ok(modelService.probe(id));
    }

    /** 未落库模型的探活（SPEC §30.6）：Agent 自带模型在配置页就取回窗口与能力位；仅对可发现供应商开放 */
    @PostMapping("/probe-draft")
    public Result<ModelVendorClient.ProbeResult> probeDraft(@RequestBody ProbeDraftRequest request) {
        return Result.ok(modelService.probeDraft(request.provider(), request.modelName(), request.apiKey()));
    }

    /** 探活入参：apiKey 只在服务端没有该供应商默认密钥时才用，且不会被回显 */
    public record ProbeDraftRequest(String provider, String modelName, String apiKey) {
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
