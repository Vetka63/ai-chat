#requires -Version 7.0
param(
    [Parameter(Mandatory)][ValidateSet('stash','review')][string]$Scenario,
    [Parameter(Mandatory)][string]$ChatId,
    [ValidateRange(1,10)][int]$Through = 6
)
# Отдельный демонстрационный набор: не заменяет строгую приёмку и старые traces.
# Идём по существующему чату; каждое сообщение сохраняется single-step скриптом.
$ErrorActionPreference = 'Stop'
$cases = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../evaluation/video-day25-scenarios.json') -Raw | ConvertFrom-Json
$case = $cases | Where-Object id -eq $Scenario
$api = 'http://localhost:8382/api/v1'
$detail = Invoke-RestMethod "$api/conversations/$ChatId" -TimeoutSec 20
if (@($detail.turns | Where-Object status -eq PENDING).Count) { throw 'Есть незавершённое сообщение.' }
for ($i = 0; $i -lt $detail.turns.Count; $i++) {
    if ($i -ge $case.questions.Count -or $detail.turns[$i].question -cne $case.questions[$i]) { throw 'Чат не совпадает с выбранным сценарием. Не изменяем чужую историю.' }
}
$failures = 0
for ($i = $detail.turns.Count; $i -lt $Through; $i++) {
    & (Join-Path $PSScriptRoot 'run-video-chat-step.ps1') -ChatId $ChatId -Question $case.questions[$i]
    $detail = Invoke-RestMethod "$api/conversations/$ChatId" -TimeoutSec 20
    $turn = $detail.turns[-1]
    if ($turn.status -ne 'COMPLETED' -or $turn.issue -or $turn.result.status -eq 'ERROR') { throw 'Техническая ошибка/PENDING. Продолжение остановлено; историю не повторяем.' }
    if ($turn.result.status -ne 'ANSWERED') { $failures++ }
    "Сценарий $Scenario, шаг $($i + 1)/10: $($turn.result.status)."
    if ($failures -ge 2) { throw 'Два непринятых ответа в этом запуске. Это неполный сценарий; сначала разберите ошибки.' }
}
"Сохранено $($detail.turns.Count) сообщений. Это реальные результаты для просмотра в UI, смысл и память требуют отдельной проверки."
