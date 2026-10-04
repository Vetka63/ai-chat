param([string]$ApiUrl = 'http://localhost:8382/api/v1', [int]$MaxOutputTokens = 2400)
$ErrorActionPreference = 'Stop'
$ragApi = $ApiUrl.TrimEnd('/')
$ragSettings = Invoke-RestMethod "$ragApi/answer-settings"
if (!$ragSettings.configured) { throw 'Нужен серверный ключ DeepSeek.' }
$ragIndexes = Invoke-RestMethod "$ragApi/indexes"
$ragIndex = $ragIndexes | Where-Object { $_.config.strategy -eq 'STRUCTURAL' -and $_.config.maxCharacters -eq 3000 -and $_.config.overlapCharacters -eq 300 } | Select-Object -First 1
if (!$ragIndex) { throw 'Нужен готовый STRUCTURAL индекс 3000/300.' }
$ragQuestions = Invoke-RestMethod "$ragApi/evaluation/questions"
if ($ragQuestions.Count -ne 10) { throw 'Ожидалось 10 вопросов.' }
$ragRunId = '{0}-{1}' -f [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'), [Guid]::NewGuid().ToString('N').Substring(0, 8)
$ragReport = @{ at = [DateTimeOffset]::UtcNow.ToString('o'); runId = $ragRunId; indexId = $ragIndex.id; model = $ragSettings.model; generationTestMaxOutputTokens = $MaxOutputTokens; supportCheckMaxOutputTokens = 2048; note = 'До 20 LLM-вызовов для 10 вопросов: генерация и отдельная проверка смысла, без rewrite/retry. Тестовый лимит генерации не меняет default null в UI. Проверяются PASSED, дословность и provenance; правильность модельных вердиктов оценивается также вручную.'; cases = @(); negative = @() }
$ragDocuments = @{}
$ragFailedCases = @()
$ragDataDirectory = Join-Path $PSScriptRoot '../data'
New-Item -ItemType Directory -Path $ragDataDirectory -Force | Out-Null
$ragReportPath = Join-Path $ragDataDirectory "day24-live-results-$ragRunId.json"
function Save-RagReport {
    [IO.File]::WriteAllText($ragReportPath, ($ragReport | ConvertTo-Json -Depth 60), [Text.UTF8Encoding]::new($false))
}
Save-RagReport
"Новый trace: $ragReportPath. До 20 платных LLM-вызовов; старые прогоны сохраняются."
function Invoke-Grounded([string]$Question, [double]$Threshold = 0.65) {
    $ragBody = @{ question = $Question; indexId = $ragIndex.id; candidateTopK = 10; finalTopK = 5; similarityThreshold = $Threshold; contextMaxCharacters = 16000; maxOutputTokens = $MaxOutputTokens; useRewrite = $false } | ConvertTo-Json
    Invoke-RestMethod "$ragApi/grounded-answers" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($ragBody)) -TimeoutSec 240
}
foreach ($ragCase in $ragQuestions) {
    $ragResult = Invoke-Grounded $ragCase.question
    $ragReport.cases += @{ case = $ragCase; result = $ragResult }
    Save-RagReport # Сохраняем и отклонённый ответ до сообщения об ошибке.
    if ($ragResult.llmStagesAttempted -gt 2) { throw 'Без rewrite ожидается не более двух LLM-стадий.' }
    if ($ragResult.status -ne 'ANSWERED') {
        if ($ragResult.claims.Count -or $ragResult.sources.Count) { throw 'Отклонённый ответ содержит публичные утверждения или источники.' }
        $ragFailedCases += $ragCase.id; "$($ragCase.id): $($ragResult.status); trace сохранён."; continue
    }
    if (!$ragResult.claims.Count -or !$ragResult.sources.Count) { throw 'Ответ без источников.' }
    if (!$ragResult.supportCheck -or $ragResult.supportCheck.status -ne 'PASSED') { throw 'ANSWERED без успешной проверки смысловой поддержки.' }
    if ($ragResult.supportCheck.claims.Count -ne $ragResult.claims.Count -or $ragResult.supportCheck.issues.Count) { throw 'Проверка смысла не покрывает все пункты или содержит ошибки.' }
    $ragCheckedIndices = @($ragResult.supportCheck.claims | ForEach-Object {
        if ($_.verdict -ne 'SUPPORTED') { throw 'В опубликованном ответе есть неподтверждённый пункт.' }
        $_.claimIndex
    } | Sort-Object)
    if (($ragCheckedIndices -join ',') -cne ((0..($ragResult.claims.Count - 1)) -join ',')) { throw 'Индексы смысловых вердиктов повторяются, пропущены или не совпадают с пунктами.' }
    if ($ragResult.llmStagesAttempted -ne 2 -or !$ragResult.generation -or !$ragResult.supportCheck.generation) { throw 'Не учтены обе LLM-стадии.' }
    if ($ragResult.totalUsage) {
        if (!$ragResult.generation.usage -or !$ragResult.supportCheck.generation.usage) { throw 'Общий usage не должен быть известен при неизвестном расходе стадии.' }
        foreach ($ragMetric in @('promptTokens', 'completionTokens', 'totalTokens')) {
            if ($ragResult.totalUsage.$ragMetric -ne ($ragResult.generation.usage.$ragMetric + $ragResult.supportCheck.generation.usage.$ragMetric)) { throw "Неверный суммарный usage: $ragMetric." }
        }
    }
    foreach ($ragClaim in $ragResult.claims) {
        if (!$ragClaim.citations.Count) { throw 'Пункт без цитаты.' }
        foreach ($ragCitation in $ragClaim.citations) {
            $ragHit = $ragResult.retrieval.included | Where-Object { $_.chunk.chunkId -ceq $ragCitation.source.chunkId } | Select-Object -First 1
            if (!$ragHit -or !$ragHit.chunk.text.Contains($ragCitation.quote)) { throw 'Цитата не из переданного контекста.' }
            if ($ragHit.chunk.source -cne $ragCitation.source.source -or $ragHit.chunk.section -cne $ragCitation.source.section) { throw 'Подмена метаданных.' }
            $ragDocId = $ragCitation.source.documentId
            if (!$ragDocuments.ContainsKey($ragDocId)) { $ragDocuments[$ragDocId] = Invoke-RestMethod "$ragApi/indexes/$($ragIndex.id)/documents/$ragDocId" }
            $ragDoc = $ragDocuments[$ragDocId]
            if ($ragDoc.text.Substring($ragCitation.canonicalStart, $ragCitation.canonicalEndExclusive - $ragCitation.canonicalStart) -cne $ragCitation.quote) { throw 'Координаты не совпадают с snapshot.' }
        }
    }
    "$($ragCase.id): $($ragResult.status); claims=$($ragResult.claims.Count); sources=$($ragResult.sources.Count); tokens=$($ragResult.totalUsage.totalTokens)"
}
foreach ($ragQuestion in @('Какая погода завтра в Самаре?', 'Как приготовить борщ?', 'Кто выиграет следующий чемпионат мира по футболу?')) {
    $ragResult = Invoke-Grounded $ragQuestion
    $ragReport.negative += @{ question = $ragQuestion; result = $ragResult }; Save-RagReport
    if ($ragResult.status -ne 'UNKNOWN' -or $ragResult.llmStagesAttempted -ne 0 -or $ragResult.claims.Count -or $ragResult.sources.Count) { throw 'Ожидался отказ без генерации и выдуманных источников.' }
}
$ragResult = Invoke-Grounded 'Как работает git stash?' 1
$ragReport.negative += @{ question = 'High threshold'; result = $ragResult }; Save-RagReport
if ($ragResult.status -ne 'UNKNOWN' -or $ragResult.llmStagesAttempted -ne 0) { throw 'Порог не блокирует слабый контекст.' }
if ($ragFailedCases.Count) { throw "Не все ответы прошли evidence: $($ragFailedCases -join ', '). Trace: $ragReportPath. Автоматического retry нет." }
"Проверены 10 ответов и 4 отказа. Trace: $ragReportPath. Проверка модели не гарантирует истину: оцените смысл и полноту также вручную."
