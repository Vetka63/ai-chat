// Публичные варианты Pydantic-схемы; их семантику задаёт политика агента на backend.
export const preferenceFields = [
  { key: 'explanation_language', label: 'Язык объяснения', options: { ru: 'Русский', en: 'English' } },
  { key: 'experience_level', label: 'Уровень подготовки', options: { beginner: 'Начинающий', intermediate: 'Средний', advanced: 'Опытный' } },
  { key: 'detail_level', label: 'Подробность', options: { concise: 'Кратко', balanced: 'Умеренно', detailed: 'Подробно' } },
  { key: 'response_format', label: 'Формат объяснения', options: { prose: 'Связный текст', bullets: 'Список', steps: 'Шаги решения' } },
  { key: 'preferred_code_language', label: 'Предпочтительный язык кода', options: { '': 'Без предпочтения', python: 'Python', java: 'Java', javascript: 'JavaScript', go: 'Go' } },
]
export const defaultPreferences = () => ({ explanation_language: 'ru', experience_level: 'intermediate',
  detail_level: 'balanced', response_format: 'prose', preferred_code_language: null, soft_constraints: [] })
