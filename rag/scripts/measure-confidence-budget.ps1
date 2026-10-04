param(
    [Parameter(Mandatory)][DateTimeOffset]$Since,
    [ValidateRange(0,2000)][int]$UnobservedReserve = 0,
    [ValidateRange(1,100000)][int]$Limit = 2000
)
# Только локальное чтение. Считает попытки стадий, а не стоимость из счёта провайдера.
# Прерванные до сохранения HTTP-вызовы задаются отдельно консервативным резервом.
$ErrorActionPreference = 'Stop'
$rows = @()
$seenTurns = [Collections.Generic.HashSet[string]]::new()
foreach ($file in Get-ChildItem (Join-Path $PSScriptRoot '../data') -Filter '*.json' | Sort-Object Name) {
    if ($file.Name -notmatch '^(day24-(support-live|replay-live|live-results|ui-live)|day25-confidence|memory-preparation-live|memory-lifecycle-live)') { continue }
    $j = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
    # Не вызываем Parse(DateTime): локализованное строковое преобразование может перепутать день и месяц.
    $at = if ($j.at) { [DateTimeOffset]$j.at } else { [DateTimeOffset]$file.LastWriteTimeUtc }
    if ($at -lt $Since) { continue }
    $calls = 0
    if ($file.Name -like 'day24-support-live*') {
        foreach ($c in $j.cases) { $calls += 1 + @($c.supportCheck.additionalGenerations | Where-Object { $null -ne $_ }).Count }
    }
    elseif ($file.Name -like 'day24-replay-live*') {
        foreach ($r in $j.records) { $calls += 1 + @($r.supportCheck.additionalGenerations | Where-Object { $null -ne $_ }).Count }
    }
    elseif ($file.Name -like 'day24-live-results*') {
        $calls = ($j.cases.result.llmStagesAttempted | Measure-Object -Sum).Sum
        $calls += ($j.negative.result.llmStagesAttempted | Measure-Object -Sum).Sum
    }
    elseif ($file.Name -like 'memory-preparation-live*') { $calls = $j.callsAttempted }
    elseif ($file.Name -like 'day24-ui-live*') {
        # UI trace имеет тот же GroundedResult; не оцениваем неизвестный расход нулём.
        $calls = $j.llmStagesAttempted
        if ($null -eq $calls) { throw "Неизвестная схема UI trace: $($file.Name)" }
    }
    else {
        foreach ($record in $j.results) {
            $turn = $record.turn
            if ($turn -and $turn.status -ne 'PENDING' -and $seenTurns.Add($turn.id)) { $calls += $turn.llmStagesAttempted }
        }
    }
    $rows += [pscustomobject]@{ file = $file.Name; observedStageAttempts = [int]$calls }
}
$observed = [int]($rows.observedStageAttempts | Measure-Object -Sum).Sum
[pscustomobject]@{
    since = $Since.ToString('o'); observedStageAttempts = $observed
    unobservedReserve = $UnobservedReserve; conservativeTotal = $observed + $UnobservedReserve
    remaining = $Limit - $observed - $UnobservedReserve; limit = $Limit
    note = 'Не счёт провайдера. Текущие незавершённые вызовы и потерянные ответы покрываются явным резервом; старые traces не повторяются.'
    files = $rows
} | ConvertTo-Json -Depth 5
