export const THEME_STORAGE_KEY = 'klever-theme'

export const themeOptions = [
  { id: 'light', name: 'Светлая', description: 'Мягкий дневной свет' },
  { id: 'dark', name: 'Тёмная', description: 'Спокойный вечерний режим' },
  {
    id: 'spring',
    name: 'Весна',
    description: 'Мята и молодая зелень',
    season: 'spring',
  },
  {
    id: 'summer',
    name: 'Лето',
    description: 'Тёплая трава и солнце',
    season: 'summer',
  },
  {
    id: 'autumn',
    name: 'Осень',
    description: 'Клён, янтарь и пряный вечер',
    season: 'autumn',
    story: {
      symbol: '🍁',
      eyebrow: 'Осенняя глава',
      title: 'Время тёплых разговоров',
      description: 'Листопад за окном, тёплый свет внутри.',
      moments: ['🍂 Листопад', '🎃 Хэллоуин', '☕ Тёплый чай'],
    },
  },
  {
    id: 'winter',
    name: 'Зима',
    description: 'Снег, огоньки и ожидание чуда',
    season: 'winter',
    story: {
      symbol: '❄️',
      eyebrow: 'Новогодняя мастерская',
      title: 'Пусть ответы немного искрятся',
      description: 'За окном снег, а Клевер уже зажёг гирлянду.',
      moments: ['🎄 Ёлка', '⛄ Снеговик', '🎁 Подарки'],
    },
  },
]

export function findTheme(themeId) {
  return themeOptions.find((theme) => theme.id === themeId) || themeOptions[0]
}
