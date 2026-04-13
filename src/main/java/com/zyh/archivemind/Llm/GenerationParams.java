package com.zyh.archivemind.Llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 生成参数
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerationParams {

    /** 采样温度，范围 0-2，默认 0.3 */
    @Builder.Default
    private Double temperature = 0.3;

    /** 最大输出 tokens，默认 2000 */
    @Builder.Default
    private Integer maxTokens = 2000;

    /** nucleus top-p，默认 0.9 */
    @Builder.Default
    private Double topP = 0.9;
}
