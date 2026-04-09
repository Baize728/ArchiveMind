package com.zyh.archivemind.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MessageDTO {
    private String role;
    private String content;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String thinkingContent;
    private String timestamp;
}
