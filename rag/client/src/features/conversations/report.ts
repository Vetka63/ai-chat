import type { Schema } from '../../api/client'
import { groundedMarkdown } from '../grounding/report'

/** Основной экспорт не включает raw JSON/промпты модели, но сохраняет цитаты и память с provenance. */
export function conversationMarkdown(d: Schema<'ConversationDetail'>): string {
  return `# ${d.conversation.title}\n\nИндекс: ${d.conversation.settings.indexId}\nSnapshot: ${d.conversation.snapshotId}\n\n## Память задачи\n\n` + d.memory.facts.map(f => `- ${f.layer} / ${f.key}: ${f.value}\n  Источник: ${f.sourceTurnId}, «${f.quote}»`).join('\n') + '\n\n' + d.turns.map((t, n) => `## Сообщение ${n + 1}\n\n${t.question}\n\n${t.issue ?? ''}\n\n${t.result ? groundedMarkdown(t.result) : t.status}\n\nВсего на сообщение: LLM-стадий=${t.llmStagesAttempted ?? 'неизвестно'}; API tokens=${t.totalUsage?.totalTokens ?? 'неизвестно'} (подготовка и весь ответ, включая исправление, если оно выполнялось).\n${t.estimatedCost ? `Общая оценка сообщения USD: ${t.estimatedCost.minimumUsd}–${t.estimatedCost.maximumUsd}; не списание.\n` : ''}\nХвост: ${t.includedHistoryTurnIds.length}; вне prompt: ${t.omittedHistoryTurnCount}.\n`).join('\n')
}
