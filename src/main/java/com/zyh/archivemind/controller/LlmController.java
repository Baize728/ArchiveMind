package com.zyh.archivemind.controller;

import com.zyh.archivemind.Llm.LlmRouter;
import com.zyh.archivemind.Llm.UserLlmPreferenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/llm")
public class LlmController {

    private final LlmRouter llmRouter;
    private final UserLlmPreferenceService preferenceService;

    public LlmController(LlmRouter llmRouter, UserLlmPreferenceService preferenceService) {
        this.llmRouter = llmRouter;
        this.preferenceService = preferenceService;
    }

    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> listProviders(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) return ResponseEntity.status(401).build();
        String userId = userDetails.getUsername();
        String currentProviderId = preferenceService.getProviderIdForUser(userId);
        List<Map<String, Object>> providers = llmRouter.getAllProviders().values().stream()
                .map(p -> Map.<String, Object>of(
                        "id", p.getProviderId(),
                        "supportsToolCalling", p.supportsToolCalling(),
                        "current", p.getProviderId().equals(currentProviderId)
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(Map.of(
                "currentProvider", currentProviderId,
                "providers", providers
        ));
    }

    @PostMapping("/providers/preference")
    public ResponseEntity<Map<String, String>> setPreference(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam String providerId) {
        if (userDetails == null) return ResponseEntity.status(401).build();
        preferenceService.setProviderForUser(userDetails.getUsername(), providerId);
        return ResponseEntity.ok(Map.of(
                "message", "已切换到: " + providerId,
                "currentProvider", providerId
        ));
    }
}
