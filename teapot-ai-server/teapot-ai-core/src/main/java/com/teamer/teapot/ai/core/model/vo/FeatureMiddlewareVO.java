package com.teamer.teapot.ai.core.model.vo;

import com.teamer.teapot.ai.core.middleware.feature.ConfigFieldSchema;
import lombok.Data;

import java.util.List;

/**
 * feature 驱动中间件的界面视图（SPEC §29.23）：schema 由配置类内省产出，
 * config 是该 Agent 当前存的原始值，configError 是它解析失败的原因（能存下但跑不起来的配置要在界面上说出来）。
 */
@Data
public class FeatureMiddlewareVO {

    private String key;
    /** feature 里的命名空间名（{@code middleware_<key>}） */
    private String featureKey;
    private String description;
    /** true = 配置本体是数组 */
    private boolean array;
    private List<ConfigFieldSchema> fields;
    /** 当前 Agent 已存的配置原始值；未配置为 null */
    private Object config;
    /** 已存配置的解析错误；配置合法或未配置为 null */
    private String configError;

    public static FeatureMiddlewareVO of(String key, String featureKey, String description,
                                         boolean array, List<ConfigFieldSchema> fields,
                                         Object config, String configError) {
        FeatureMiddlewareVO vo = new FeatureMiddlewareVO();
        vo.setKey(key);
        vo.setFeatureKey(featureKey);
        vo.setDescription(description);
        vo.setArray(array);
        vo.setFields(fields);
        vo.setConfig(config);
        vo.setConfigError(configError);
        return vo;
    }
}
