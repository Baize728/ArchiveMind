package com.zyh.archivemind.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SessionDetailDTO {
    private String sessionId;
    private String title;
    private LocalDateTime createdAt;
    private List<MessageDTO> messages;
}
