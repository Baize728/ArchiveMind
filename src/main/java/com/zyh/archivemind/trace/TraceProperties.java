package com.zyh.archivemind.trace;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Trace 采集配置
 * 前缀 trace.*，默认值满足 plan.md T0-1 验收（在线落库 + 异步批量 + 脱敏）。
 */
@Component
@ConfigurationProperties(prefix = "trace")
@Data
public class TraceProperties {

    /** 在线对话是否落库（默认 true） */
    private boolean onlinePersist = true;

    /** 后台批量落库刷新间隔（毫秒） */
    private long flushIntervalMs = 500;

    /** 每批最大写入条数 */
    private int batchSize = 200;

    /** 内存队列容量（超出丢弃最旧，保护内存） */
    private int queueCapacity = 10000;

    /** 单条 payload 最大字符数，超出截断 */
    private int maxPayloadLength = 8000;

    /** 采样率 0~1，1 表示全采 */
    private double samplingRate = 1.0;

    /** 是否对落库内容做脱敏 */
    private boolean maskEnabled = true;

    /** 敏感词/凭据关键词（命中 "key=value" 形式的值将被替换为 ***） */
    private List<String> sensitiveKeywords = new ArrayList<>();
}
