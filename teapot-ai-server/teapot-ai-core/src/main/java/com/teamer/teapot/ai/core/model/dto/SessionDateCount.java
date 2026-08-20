package com.teamer.teapot.ai.core.model.dto;

/**
 * 会话按日统计（Profile 热力图数据源）：date=yyyy-MM-dd，count=当日会话数。
 */
public record SessionDateCount(String date, long count) {
}
