package com.zyh.archivemind.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("三参数构造应正确设置 thinkingContent")
    void shouldSetThinkingContentViaAllArgsConstructor() {
        Message msg = new Message("assistant", "回答", "思考过程");

        assertThat(msg.getRole()).isEqualTo("assistant");
        assertThat(msg.getContent()).isEqualTo("回答");
        assertThat(msg.getThinkingContent()).isEqualTo("思考过程");
    }

    @Test
    @DisplayName("双参数构造应保持 thinkingContent 为 null")
    void shouldLeaveThinkingContentNullWithTwoArgConstructor() {
        Message msg = new Message("user", "你好");

        assertThat(msg.getRole()).isEqualTo("user");
        assertThat(msg.getContent()).isEqualTo("你好");
        assertThat(msg.getThinkingContent()).isNull();
    }

    @Test
    @DisplayName("thinkingContent 字段应正确序列化到 JSON")
    void shouldSerializeThinkingContent() throws Exception {
        Message msg = new Message("assistant", "回答", "思考过程");

        String json = objectMapper.writeValueAsString(msg);

        assertThat(json).contains("\"thinkingContent\":\"思考过程\"");
    }

    @Test
    @DisplayName("thinkingContent 字段应正确从 JSON 反序列化")
    void shouldDeserializeThinkingContent() throws Exception {
        String json = "{\"role\":\"assistant\",\"content\":\"回答\",\"thinkingContent\":\"思考\"}";

        Message msg = objectMapper.readValue(json, Message.class);

        assertThat(msg.getThinkingContent()).isEqualTo("思考");
        assertThat(msg.getRole()).isEqualTo("assistant");
        assertThat(msg.getContent()).isEqualTo("回答");
    }

    @Test
    @DisplayName("反序列化缺少 thinkingContent 的 JSON 应得到 null")
    void shouldDeserializeJsonWithoutThinkingContent() throws Exception {
        String json = "{\"role\":\"user\",\"content\":\"你好\"}";

        Message msg = objectMapper.readValue(json, Message.class);

        assertThat(msg.getThinkingContent()).isNull();
    }
}
