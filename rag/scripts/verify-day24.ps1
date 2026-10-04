param([string]$ApiUrl = 'http://localhost:8382/api/v1', [int]$MaxOutputTokens = 2400)
$ErrorActionPreference = 'Stop'
$ragApi = $ApiUrl.TrimEnd('/')
$ragSettings = Invoke-RestMethod "$ragApi/answer-settings"
if (!$ragSettings.configured) { throw 'Нужен серверный ключ DeepSeek.' }
$ragIndexes = Invoke-RestMethod "$ragApi/indexes"
$ragIndex = $ragIndexes | Where-Object { $_.config.strategy -eq 'STRUCTURAL' -and $_.config.maxCharacters -eq 3000 } | Select-Object -First 1
if (!$ragIndex) { throw 'Нужен готовый STRUCTURAL индекс 3000/300.' }
$ragQuestions = Invoke-RestMethod "$ragApi/evaluation/questions"
if ($ragQuestions.Count -ne 10) { throw 'Ожидалось 10 вопросов.' }
$ragReport = @{ at = [DateTimeOffset]::UtcNow.ToString('o'); indexId = $ragIndex.id; model = $ragSettings.model; note = '10 paid calls; 2400 is a test cap, production default null. Exact quotes and source provenance are automated; semantic support is reviewed manually.'; cases = @(); negative = @() }
$ragDocuments = @{}
$ragFailedCases = @()
$ragDataDirectory = Join-Path $PSScriptRoot '../data'
New-Item -ItemType Directory -Path $ragDataDirectory -Force | Out-Null
function Save-RagReport {
    [IO.File]::WriteAllText((Join-Path $ragDataDirectory 'day24-live-results.json'), ($ragReport | ConvertTo-Json -Depth 60), [Text.UTF8Encoding]::new($false))
}
function Invoke-Grounded([string]$Question, [double]$Threshold = 0.65) {
    $ragBody = @{ question = $Question; indexId = $ragIndex.id; candidateTopK = 10; finalTopK = 5; similarityThreshold = $Threshold; contextMaxCharacters = 16000; maxOutputTokens = $MaxOutputTokens; useRewrite = $false } | ConvertTo-Json
    Invoke-RestMethod "$ragApi/grounded-answers" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($ragBody)) -TimeoutSec 240
}
foreach ($ragCase in $ragQuestions) {
    $ragResult = Invoke-Grounded $ragCase.question
    $ragReport.cases += @{ case = $ragCase; result = $ragResult }
    Save-RagReport # Preserve even invalid evidence before reporting a failure.
    if ($ragResult.status -ne 'ANSWERED') { $ragFailedCases += $ragCase.id; "$($ragCase.id): $($ragResult.status); saved trace available."; continue }
    if (!$ragResult.claims.Count -or !$ragResult.sources.Count) { throw 'Ответ без источников.' }
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
if ($ragFailedCases.Count) { throw "Не все ответы прошли evidence: $($ragFailedCases -join ', '). Все результаты сохранены, автоматического retry нет." }
'Проверены 10 ответов и 4 отказа. Результат: data/day24-live-results.json. Совпадение смысла с цитатами оцените отдельно.'
