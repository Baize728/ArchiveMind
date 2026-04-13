package com.zyh.archivemind.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Message {
    private String role;
    private String content;
    private String thinkingContent;

    /**
     * 兼容旧的双参数构造方式
     */
    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }
}
