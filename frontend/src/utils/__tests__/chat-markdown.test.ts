import { describe, expect, it } from 'vitest';
import { normalizeMarkdownHeadings, prepareChatMarkdown } from '@/utils/chat-markdown';

describe('chat markdown preparation', () => {
  it('normalizes malformed headings without changing fenced code', () => {
    const markdown = '#标题\n\n```md\n#代码标题\n```\n\n## 已有空格';

    expect(normalizeMarkdownHeadings(markdown)).toBe('# 标题\n\n```md\n#代码标题\n```\n\n## 已有空格');
  });

  it('converts source citations to markdown links and keeps source metadata', () => {
    const result = prepareChatMarkdown('说明 **重点** (来源#2: 面渣 (分布式).pdf)。');

    expect(result.content).toContain('[(来源#2: 面渣 (分布式).pdf)](#source-file-0)');
    expect(result.sourceFiles).toEqual([{ id: 'source-file-0', fileName: '面渣 (分布式).pdf' }]);
  });

  it('does not transform citations inside fenced code or inline code', () => {
    const markdown = '```text\n(来源#1: code.pdf)\n```\n\n`(来源#2: inline.pdf)`';
    const result = prepareChatMarkdown(markdown);

    expect(result.content).toBe(markdown);
    expect(result.sourceFiles).toHaveLength(0);
  });
});
