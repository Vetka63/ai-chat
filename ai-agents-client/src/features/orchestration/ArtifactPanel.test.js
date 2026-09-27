import { enableAutoUnmount, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import ArtifactPanel from './ArtifactPanel.vue'

enableAutoUnmount(afterEach)
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

it('shows the artifact lineage and downloads a saved Go report', async () => {
  let downloadedName
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function () {
    downloadedName = this.download
  })
  vi.stubGlobal('URL', {
    createObjectURL: vi.fn(() => 'blob:report'),
    revokeObjectURL: vi.fn(),
  })
  const wrapper = mount(ArtifactPanel, { props: { artifacts: [
    { id: 'collection-1', kind: 'collection', title: 'Космос', payload: { games: [{}, {}] } },
    { id: 'report-1', kind: 'report', title: 'Общий отчёт', payload: {
      file_name: 'report-1.md', report_markdown: '# Общий отчёт',
    } },
  ] } })

  expect(wrapper.text()).toContain('Подборки · 1')
  expect(wrapper.text()).toContain('Отчёты · 1')
  expect(wrapper.text()).toContain('2 игр')
  await wrapper.get('button[aria-label="Скачать отчёт Общий отчёт"]').trigger('click')
  expect(downloadedName).toBe('report-1.md')
  expect(URL.createObjectURL).toHaveBeenCalledOnce()
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:report')
})
