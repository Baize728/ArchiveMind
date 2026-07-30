package com.zyh.archivemind.eval.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetricStat {
    private Double value;
    private int n;
    private String scope;   // e.g. "answer_generate_only" for tokenCost
}
