package com.zyh.archivemind.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class GroupedSessionListDTO {
    private List<SessionDTO> today;
    private List<SessionDTO> week;
    private List<SessionDTO> month;
    private Map<String, List<SessionDTO>> earlier;
}
