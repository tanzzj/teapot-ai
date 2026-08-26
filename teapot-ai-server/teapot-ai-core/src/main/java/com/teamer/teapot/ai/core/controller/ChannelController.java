package com.teamer.teapot.ai.core.controller;

import com.teamer.teapot.ai.common.model.Result;
import com.teamer.teapot.ai.core.channel.ChannelHub;
import com.teamer.teapot.ai.core.config.ConfigCryptoService;
import com.teamer.teapot.ai.core.model.ChannelConfigDO;
import com.teamer.teapot.ai.core.service.ChannelConfigService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Channel 连接器记录接口（SPEC §24.4，/api/channel-config）：
 * list/create/update/delete 全部 admin（RBAC yml 未向 developer/viewer 放开）；
 * registry 轻量名单供 Agent 配置下拉（developer/viewer 可读，模式同 §22.2 *-record-names）。
 * GET 回显脱敏：app_secret 只回掩码，任何接口不返回明文。
 */
@RestController
@RequestMapping("/api/channel-config")
public class ChannelController {

    private final ChannelConfigService channelConfigService;
    private final ChannelHub channelHub;

    public ChannelController(ChannelConfigService channelConfigService, ChannelHub channelHub) {
        this.channelConfigService = channelConfigService;
        this.channelHub = channelHub;
    }

    /** 记录列表（仅 admin）：appSecret 回掩码 + configured 布尔 */
    @GetMapping("/list")
    public Result<Map<String, Object>> list() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> records = new ArrayList<>();
        for (ChannelConfigDO row : channelConfigService.list()) {
            // 解密视图：掩码回显与 configured 判定（按渠道类型规则，§24 修订）共用
            ChannelConfigDO plain = channelConfigService.getPlain(row.getName());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.getName());
            item.put("channelType", row.getChannelType());
            item.put("appKey", row.getAppKey());
            item.put("robotCode", row.getRobotCode());
            item.put("remark", row.getRemark());
            item.put("appSecretMasked", ConfigCryptoService.mask(plain == null ? null : plain.getAppSecret()));
            item.put("configured", ChannelConfigService.configured(plain));
            item.put("updatedAt", row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
            records.add(item);
        }
        result.put("records", records);
        return Result.ok(result);
    }

    /** 新建记录（仅 admin）：appSecret 加密入库 */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody ChannelConfigDO record) {
        channelConfigService.create(record);
        return list();
    }

    /** 更新记录（仅 admin）：appSecret 留空不修改；凭证变更后重启引用该记录的 Agent channel */
    @PutMapping
    public Result<Map<String, Object>> update(@RequestBody ChannelConfigDO record) {
        channelConfigService.update(record);
        for (String agentKey : channelConfigService.referencingAgents(record.getName())) {
            channelHub.sync(agentKey);
        }
        return list();
    }

    /** 删除记录（仅 admin）：被 Agent 引用时由 Service 拒绝 */
    @DeleteMapping("/{name}")
    public Result<Map<String, Object>> delete(@PathVariable("name") String name) {
        channelConfigService.delete(name);
        return list();
    }

    /** 测试连接（仅 admin，§24.10）：轻量调平台 API 验凭证/网络，不落库；凭证留空时回落库内解密值 */
    @PostMapping("/test")
    public Result<Map<String, Object>> test(@RequestBody ChannelConfigDO record) {
        ChannelConfigDO merged = new ChannelConfigDO();
        merged.setChannelType(record.getChannelType());
        merged.setAppKey(record.getAppKey());
        merged.setAppSecret(record.getAppSecret());
        if (isBlank(merged.getAppSecret()) && !isBlank(record.getName())) {
            ChannelConfigDO plain = channelConfigService.getPlain(record.getName());
            if (plain != null) {
                if (isBlank(merged.getAppSecret())) {
                    merged.setAppSecret(plain.getAppSecret());
                }
                if (isBlank(merged.getAppKey())) {
                    merged.setAppKey(plain.getAppKey());
                }
                if (isBlank(merged.getChannelType())) {
                    merged.setChannelType(plain.getChannelType());
                }
            }
        }
        return Result.ok(channelConfigService.testConnection(merged));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 轻量名单（developer/viewer 可读）：仅名称/类型，供 Agent 配置下拉选择 */
    @GetMapping("/registry")
    public Result<List<Map<String, Object>>> registry() {
        List<Map<String, Object>> records = new ArrayList<>();
        for (ChannelConfigDO row : channelConfigService.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.getName());
            item.put("channelType", row.getChannelType());
            records.add(item);
        }
        return Result.ok(records);
    }
}
