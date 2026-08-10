package com.zyh.archivemind.clarify;

import com.zyh.archivemind.config.AiProperties;
import com.zyh.archivemind.model.SessionState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClarifyRuleServiceTest {

    private final ClarifyRuleService service = new ClarifyRuleService(new AiProperties());

    @Test
    void completeConceptQuestionShouldNotAskForDomain() {
        List<String> missing = service.missingSlots(
                "什么是 CAP 理论？",
                SlotBundle.empty(),
                SessionState.fresh());

        assertTrue(missing.isEmpty());
    }

    @Test
    void vagueQuestionShouldStillAskForDomainAndEntity() {
        List<String> missing = service.missingSlots(
                "帮我查一下那个规定",
                SlotBundle.empty(),
                SessionState.fresh());

        assertEquals(List.of("domain", "entity"), missing);
    }

    @Test
    void completeQuestionWithBackendContextShouldNotAskForDomain() {
        List<String> missing = service.missingSlots(
                "CAP 理论在后端开发中怎么应用？",
                new SlotBundle("it", null, null, null),
                SessionState.fresh());

        assertTrue(missing.isEmpty());
    }
}
