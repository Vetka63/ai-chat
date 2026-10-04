import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, type Schema } from '../../api/client'

/** Серверные документы и задания; отсутствие LLM-чата не подменяется локальной памятью UI. */
export const useLabStore = defineStore('lab', () => {
  const corpus = ref<Schema<'CorpusInfo'> | null>(null)
  const documents = ref<Schema<'DocumentInfo'>[]>([])
  const indexes = ref<Schema<'IndexInfo'>[]>([])
  const jobs = ref<Schema<'IndexJob'>[]>([])
  async function load() {
    const [c, d, i, j] = await Promise.all([api.corpus(), api.documents(), api.indexes(), api.jobs()])
    corpus.value = c; documents.value = d; indexes.value = i; jobs.value = j
  }
  async function refresh() {
    const [i, j] = await Promise.all([api.indexes(), api.jobs()])
    indexes.value = i; jobs.value = j
  }
  return { corpus, documents, indexes, jobs, load, refresh }
})
