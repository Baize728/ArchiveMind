export interface ChatSourceFile {
  fileName: string;
  id: string;
}

export interface PreparedChatMarkdown {
  content: string;
  sourceFiles: ChatSourceFile[];
}

const FENCE_PATTERN = /^\s{0,3}(`{3,}|~{3,})/;
const SOURCE_PATTERN = /(\(|（)来源\s*#(\d+)\s*[:：]\s*((?:[^()\r\n]|\([^()\r\n]*\))+?)(\)|）)/g;

function escapeMarkdownLinkText(text: string) {
  return text.replace(/([\\`*_[\]<>])/g, '\\$1');
}

function replaceSourceLinksInText(text: string, sourceFiles: ChatSourceFile[]) {
  return text.replace(SOURCE_PATTERN, (...match) => {
    const [, opening, sourceNum, rawFileName, closing] = match;
    const fileName = rawFileName.trim();
    const id = `source-file-${sourceFiles.length}`;

    sourceFiles.push({ fileName, id });

    const label = `${opening}来源#${sourceNum}: ${fileName}${closing}`;
    return `[${escapeMarkdownLinkText(label)}](#${id})`;
  });
}

function replaceSourceLinksInLine(line: string, sourceFiles: ChatSourceFile[]) {
  const inlineCodePattern = /(`+)([^`]*?)\1/g;
  let result = '';
  let lastIndex = 0;

  for (const match of line.matchAll(inlineCodePattern)) {
    const matchIndex = match.index ?? 0;
    result += replaceSourceLinksInText(line.slice(lastIndex, matchIndex), sourceFiles);
    result += match[0];
    lastIndex = matchIndex + match[0].length;
  }

  return result + replaceSourceLinksInText(line.slice(lastIndex), sourceFiles);
}

export function normalizeMarkdownHeadings(markdown: string) {
  const lines = markdown.replace(/\r\n?/g, '\n').split('\n');
  let fenceCharacter: string | null = null;

  return lines
    .map(line => {
      const fenceMatch = line.match(FENCE_PATTERN);

      if (fenceMatch) {
        const currentFenceCharacter = fenceMatch[1][0];

        if (fenceCharacter === null) {
          fenceCharacter = currentFenceCharacter;
        } else if (fenceCharacter === currentFenceCharacter) {
          fenceCharacter = null;
        }

        return line;
      }

      if (fenceCharacter !== null) {
        return line;
      }

      return line.replace(/^(\s{0,3})(#{1,6})(?!#)(?=\S)/, '$1$2 ');
    })
    .join('\n');
}

export function prepareChatMarkdown(markdown: string): PreparedChatMarkdown {
  const sourceFiles: ChatSourceFile[] = [];
  const lines = normalizeMarkdownHeadings(markdown).split('\n');
  let fenceCharacter: string | null = null;

  const content = lines
    .map(line => {
      const fenceMatch = line.match(FENCE_PATTERN);

      if (fenceMatch) {
        const currentFenceCharacter = fenceMatch[1][0];

        if (fenceCharacter === null) {
          fenceCharacter = currentFenceCharacter;
        } else if (fenceCharacter === currentFenceCharacter) {
          fenceCharacter = null;
        }

        return line;
      }

      if (fenceCharacter !== null) {
        return line;
      }

      return replaceSourceLinksInLine(line, sourceFiles);
    })
    .join('\n');

  return { content, sourceFiles };
}
