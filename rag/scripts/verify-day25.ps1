param([string]$ApiUrl = 'http://localhost:8382/api/v1', [ValidateSet('Start','Continue','All')] [string]$Phase = 'All', [int]$MaxOutputTokens = 2400)
$ErrorActionPreference = 'Stop'
$ragApi = $ApiUrl.TrimEnd('/')
$ragReady = $false
for ($ragHealthAttempt = 0; $ragHealthAttempt -lt 30; $ragHealthAttempt++) {
    try { Invoke-RestMethod "$ragApi/indexes" -TimeoutSec 5 | Out-Null; $ragReady = $true; break } catch { Start-Sleep -Seconds 1 }
}
if (!$ragReady) { throw 'Backend не готов. Health/readiness не восстановились за bounded wait.' }
$ragCases = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../evaluation/day25-scenarios.json') -Raw | ConvertFrom-Json
$ragData = Join-Path $PSScriptRoot '../data'
New-Item -ItemType Directory -Path $ragData -Force | Out-Null
$ragReportPath = Join-Path $ragData 'day25-live-results.json'
$ragDocuments = @{}
function Post-Rag([string]$Path, $Body) { Invoke-RestMethod "$ragApi$Path" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20))) -TimeoutSec 300 }
function Save-RagReport { [IO.File]::WriteAllText($ragReportPath, ($ragReport | ConvertTo-Json -Depth 90), [Text.UTF8Encoding]::new($false)) }
if ($Phase -eq 'Continue') { $ragReport = Get-Content -LiteralPath $ragReportPath -Raw | ConvertFrom-Json -AsHashtable }
else {
    $ragIndexList = Invoke-RestMethod "$ragApi/indexes"
    $ragIndex = $ragIndexList | Where-Object { $_.config.strategy -eq 'STRUCTURAL' -and $_.config.maxCharacters -eq 3000 } | Select-Object -First 1
    if (!$ragIndex) { throw 'Нужен STRUCTURAL индекс 3000/300.' }
    $ragReport = @{ at = [DateTimeOffset]::UtcNow.ToString('o'); indexId = $ragIndex.id; note = '2 scenarios x 12 user turns; max 48 paid calls. 2400 is answer test cap only. No retries. Exact quotes/provenance automatic, meaning reviewed separately.'; chats = @{}; results = @(); checks = @() }
    foreach ($ragCase in $ragCases) {
        $ragChat = Post-Rag '/conversations' @{ title = "День25 проверка $($ragCase.id) $([DateTime]::Now.ToString('HHmmss'))"; settings = @{ indexId = $ragIndex.id; historyTurns = 6; historyMaxCharacters = 10000; maxOutputTokens = $MaxOutputTokens } }
        $ragReport.chats[$ragCase.id] = $ragChat.conversation.id
    }
    Save-RagReport
}
$ragBegin = if ($Phase -eq 'Continue') { 6 } else { 0 }
$ragEnd = if ($Phase -eq 'Start') { 5 } else { 11 }
foreach ($ragCase in $ragCases) {
    $ragId = $ragReport.chats[$ragCase.id]
    $ragChat = Invoke-RestMethod "$ragApi/conversations/$ragId"
    if ($ragChat.turns.Count -ne $ragBegin) { throw 'Неожиданная история, повторный paid запуск остановлен.' }
    for ($ragN = $ragBegin; $ragN -le $ragEnd; $ragN++) {
        $ragRequestId = [Guid]::NewGuid().ToString()
        $ragChat = Post-Rag "/conversations/$ragId/turns" @{ requestId = $ragRequestId; question = $ragCase.questions[$ragN]; expectedRevision = $ragChat.conversation.revision }
        $ragTurn = $ragChat.turns[-1]
        $ragReport.results += @{ scenario = $ragCase.id; position = $ragN + 1; conversationId = $ragId; turn = $ragTurn }; Save-RagReport
        if ($ragTurn.issue -or $ragTurn.preparation.issues.Count -or !$ragTurn.result.retrieval) { throw "Подготовка/поиск сломаны: $($ragCase.id) $($ragN + 1). Trace сохранён." }
        if ($ragTurn.result.request.question -cne $ragCase.questions[$ragN] -or $ragTurn.result.retrieval.searchQuery -cne $ragTurn.preparation.query) { throw 'Оригинальный и contextual вопросы перепутаны.' }
        foreach ($ragExpectation in $ragCase.queryExpectations | Where-Object { $_.position -eq $ragN + 1 }) {
            if ($ragTurn.preparation.query -notmatch $ragExpectation.pattern) { throw "Потеряна тема короткого продолжения: $($ragCase.id) $($ragN + 1). Trace сохранён для review." }
        }
        foreach ($ragFact in $ragChat.memory.facts) {
            $ragOrigin = $ragChat.turns | Where-Object { $_.id -ceq $ragFact.sourceTurnId } | Select-Object -First 1
            if (!$ragOrigin -or !$ragOrigin.question.Contains($ragFact.quote)) { throw 'Память ссылается не на user message.' }
        }
        if ($ragTurn.result.status -eq 'ANSWERED') {
            if (!$ragTurn.result.sources.Count) { throw 'Технический ответ без источников.' }
            foreach ($ragClaim in $ragTurn.result.claims) { foreach ($ragCitation in $ragClaim.citations) {
                $ragHit = $ragTurn.result.retrieval.included | Where-Object { $_.chunk.chunkId -ceq $ragCitation.source.chunkId } | Select-Object -First 1
                if (!$ragHit -or !$ragHit.chunk.text.Contains($ragCitation.quote)) { throw 'Цитата не из нового контекста.' }
                $ragDocId = $ragCitation.source.documentId
                if (!$ragDocuments.ContainsKey($ragDocId)) { $ragDocuments[$ragDocId] = Invoke-RestMethod "$ragApi/indexes/$($ragReport.indexId)/documents/$ragDocId" }
                if ($ragDocuments[$ragDocId].text.Substring($ragCitation.canonicalStart, $ragCitation.canonicalEndExclusive - $ragCitation.canonicalStart) -cne $ragCitation.quote) { throw 'Snapshot mismatch.' }
            } }
        } elseif ($ragTurn.result.claims.Count -or $ragTurn.result.sources.Count) { throw 'Невалидный ответ содержит публичные факты.' }
        if ($ragTurn.totalUsage -and $ragTurn.totalUsage.totalTokens -ne $ragTurn.preparation.usage.totalTokens + $ragTurn.result.totalUsage.totalTokens) { throw 'Неверный учёт usage.' }
        "$($ragCase.id) $($ragN + 1): $($ragTurn.result.status); facts=$($ragChat.memory.facts.Count); tail=$($ragTurn.includedHistoryTurnIds.Count); tokens=$($ragTurn.totalUsage.totalTokens)"
    }
    $ragGoal = $ragChat.memory.facts | Where-Object { $_.layer -eq 'GOAL' } | Select-Object -First 1
    if (!$ragGoal -or $ragGoal.value -notmatch $ragCase.goalContains) { throw 'Цель потеряна.' }
    if (!($ragChat.memory.facts | Where-Object { $_.layer -eq 'CONSTRAINTS' -and $_.value -match $ragCase.constraintContains })) { throw 'Ограничение задачи потеряно.' }
    if ($Phase -ne 'Start') {
        if ($ragChat.turns.Count -ne 12 -or $ragChat.turns[-1].omittedHistoryTurnCount -lt 5) { throw 'Не проверен длинный хвост.' }
        if ($ragChat.turns[-1].includedHistoryTurnIds -contains $ragGoal.sourceTurnId) { throw 'Цель должна сохраняться за пределами хвоста.' }
    }
    $ragReport.checks += "$($ragCase.id): goal/provenance/history OK ($Phase)"; Save-RagReport
}
if ($Phase -eq 'Start') { 'Первые 6 обменов в обоих чатах сохранены. Перезапустите backend и запустите -Phase Continue.' }
else { 'Завершены два диалога по 12 user turns. Trace: data/day25-live-results.json. Оцените смысловую поддержку отдельно.' }
