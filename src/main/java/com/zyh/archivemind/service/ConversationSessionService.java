package com.zyh.archivemind.service;

import com.zyh.archivemind.dto.GroupedSessionListDTO;
import com.zyh.archivemind.dto.SessionDTO;
import com.zyh.archivemind.dto.SessionDetailDTO;

public interface ConversationSessionService {

    SessionDTO createSession(String userId);

    GroupedSessionListDTO listSessions(String userId);

    SessionDetailDTO switchSession(String userId, String sessionId);

    String getActiveSessionId(String userId);

    void updateTitle(String userId, String sessionId, String title);

    void autoGenerateTitle(String sessionId, String firstMessage);

    void deleteSession(String userId, String sessionId);

    void refreshSessionTTL(String userId, String sessionId);
}
