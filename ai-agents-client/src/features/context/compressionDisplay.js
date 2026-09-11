// Нумерация истории включает обе роли; номер запроса считает только сообщения пользователя.
export function requestNumber(messages, index) {
  if (!Number.isInteger(index) || !messages?.[index] || messages[index].role !== 'user') return null
  return messages.slice(0, index + 1).filter(message => message.role === 'user').length
}

export function compressionDescription(run, runs = []) {
  const details = run.compression
  const success = run.status === 'success'
  if (details) {
    return `${success ? 'Сжаты' : 'Попытка сжать'} сообщения истории №${details.segment_start}–${details.segment_end}. ` +
      (success ? `Сводка версии ${details.revision} охватывает сообщения №1–${details.segment_end}. ` : 'Успешное обновление сводки не подтверждено. ') +
      `${success ? 'Оставлено' : 'Планировалось оставить'} целиком: ${details.retained_messages} сообщений. ` +
      `Настройки этой попытки: оставлять минимум ${details.keep_last}, порог сжатия ${details.summarize_every}.`
  }
  // Старые summary-записи не хранили диапазон. Итог можно подтвердить только снимком основного вызова.
  const response = runs.find(item => item.purpose !== 'summary' && item.user_index === run.user_index)
  const covered = response?.estimate?.summarized_messages
  if (success && covered > 0) {
    return `Сводка охватывает сообщения истории №1–${covered}; целиком передано ${run.user_index - covered} сообщений истории. Диапазон новой порции и настройки не сохранены в этой старой записи.`
  }
  return 'Диапазон сообщений и настройки не сохранены в этой старой записи.'
}

export const callType = run => run.purpose === 'summary' ? 'Сжатие' : 'Ответ'
export const callStatus = run => ({ success: 'Успешно', error: 'Ошибка', pending: 'В работе', interrupted: 'Прервано' }[run.status] || run.status || '—')
