#requires -Version 7.0
param()
# Компактный демонстрационный набор. Старый строгий набор не изменяется.
# Первое наблюдение уже оплачено и включается как сохранённое, а не повторяется.
$ErrorActionPreference = 'Stop'
$initial = Join-Path $PSScriptRoot '../data/video-day24-20261005T062023394-a7b6c1ac-result.json'
if (!(Test-Path -LiteralPath $initial)) { throw 'Нет исходного ответа. Это скрипт продолжения конкретного прогона 05.10, не универсальная проверка.' }
$questions = @(
    'Какие файлы git stash сохраняет по умолчанию? Ответь одним коротким пунктом.',
    'Как сохранить неотслеживаемые файлы через git stash? Ответь одним коротким пунктом.',
    'Какая опция git stash включает также игнорируемые файлы? Ответь одним коротким пунктом.',
    'Выполняет ли git fetch слияние с текущими наработками? Ответь кратко только об этом.',
    'Я выполнил git add file.txt, затем ещё раз изменил file.txt. Какая версия попадёт в коммит без повторного git add? Один краткий пункт.',
    'Что представляет собой ветка в Git? Дай краткое определение одним пунктом.',
    'Какой алгоритм поиска использует git bisect? Один краткий пункт.',
    'Что позволяет сделать подмодуль Git? Ответь одним коротким пунктом.',
    'Что показывает параметр git log -p? Один краткий пункт.'
)
$path = Join-Path $PSScriptRoot "../data/video-day24-set-$([DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfff')).json"
$report = @{ at = [DateTimeOffset]::UtcNow.ToString('o'); note = '10 компактных вопросов, включая ранее оплаченный сегодняшний git status. НЕ замена исходной строгой приёмки. Все исходы сохраняются, автоматического retry нет.'; cases = @(@{ case = @{id='status'}; result = (Get-Content -LiteralPath $initial -Raw | ConvertFrom-Json); importFile = [IO.Path]::GetFullPath($initial) }); negative = @() }
function Save-Set { [IO.File]::WriteAllText($path, ($report | ConvertTo-Json -Depth 100), [Text.UTF8Encoding]::new($false)) }
Save-Set
$failed = 0
foreach ($question in $questions) {
    $run = & (Join-Path $PSScriptRoot 'run-video-grounding-case.ps1') -Question $question -PassThru
    $result = Get-Content -LiteralPath $run.ResultPath -Raw | ConvertFrom-Json
    $report.cases += @{ case = @{id="demo-$($report.cases.Count + 1)"}; result = $result; importFile = $run.ResultPath }
    Save-Set
    if ($result.status -ne 'ANSWERED') { $failed++ }
    if ($failed -ge 2 -or $result.status -eq 'ERROR') { throw "Прогон остановлен для разбора отказов. Все реальные результаты: $path" }
}
Write-Host "Набор завершён: $path. Проверка смысла и цитат проводится отдельно."
