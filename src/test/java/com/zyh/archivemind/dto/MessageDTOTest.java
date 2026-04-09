package com.zyh.archivemind.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageDTOTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("thinkingContent 字段应正确序列化到 JSON")
    void shouldSerializeThinkingContent() throws Exception {
        MessageDTO dto = MessageDTO.builder()
                .role("assistant")
                .content("回答内容")
                .thinkingContent("思考过程")
                .timestamp("2025-01-01T10:00:00")
                .build();

        String json = objectMapper.writeValueAsString(dto);

        assertThat(json).contains("\"thinkingContent\":\"思考过程\"");
        assertThat(json).contains("\"role\":\"assistant\"");
        assertThat(json).contains("\"content\":\"回答内容\"");
    }

    @Test
    @DisplayName("thinkingContent 字段应正确从 JSON 反序列化")
    void shouldDeserializeThinkingContent() throws Exception {
        String json = "{\"role\":\"assistant\",\"content\":\"回答\",\"thinkingContent\":\"思考\",\"timestamp\":\"2025-01-01T10:00:00\"}";

        MessageDTO dto = objectMapper.readValue(json, MessageDTO.class);

        assertThat(dto.getThinkingContent()).isEqualTo("思考");
        assertThat(dto.getRole()).isEqualTo("assistant");
        assertThat(dto.getContent()).isEqualTo("回答");
        assertThat(dto.getTimestamp()).isEqualTo("2025-01-01T10:00:00");
    }

    @Test
    @DisplayName("null thinkingContent 时 JSON 输出不应包含该字段")
    void shouldExcludeNullThinkingContentFromJson() throws Exception {
        MessageDTO dto = MessageDTO.builder()
                .role("assistant")
                .content("回答内容")
                .timestamp("2025-01-01T10:00:00")
                .build();

        String json = objectMapper.writeValueAsString(dto);

        assertThat(json).doesNotContain("thinkingContent");
        assertThat(json).contains("\"role\":\"assistant\"");
        assertThat(json).contains("\"content\":\"回答内容\"");
    }

    @Test
    @DisplayName("反序列化缺少 thinkingContent 的 JSON 应得到 null")
    void shouldDeserializeJsonWithoutThinkingContent() throws Exception {
        String json = "{\"role\":\"user\",\"content\":\"你好\",\"timestamp\":\"2025-01-01T10:00:00\"}";

        MessageDTO dto = objectMapper.readValue(json, MessageDTO.class);

        assertThat(dto.getThinkingContent()).isNull();
        assertThat(dto.getRole()).isEqualTo("user");
        assertThat(dto.getContent()).isEqualTo("你好");
    }
}
