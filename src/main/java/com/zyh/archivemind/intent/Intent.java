package com.zyh.archivemind.intent;

/**
 * ArchiveMind 意图识别枚举（T1-1）。
 * 借鉴 Diet-Agent §8.1 的 6 类意图，裁剪为 RAG 适用的 4 类。
 * 划分依据：按链路行为差异切，不按语义细分。
 */
public enum Intent {
    /** 知识问答，走 RAG AgentExecutor（主链路） */
    KNOWLEDGE_QA,
    /** 闲聊，轻量 LLM 直回，零检索零工具 */
    CHITCHAT,
    /** 文档操作，一期走 AgentExecutor（打标记），T2 独立分流 */
    DOC_OPERATION,
    /** 模糊/信息不足，一期返回提示文案，T1-2 接澄清追问 */
    AMBIGUOUS
}
