param([string]$ApiUrl='http://localhost:8382', [int]$MaxOutputTokens=1200)
$ErrorActionPreference='Stop'
$ragApi=$ApiUrl.TrimEnd('/')+'/api/v1'
$ragSettings=Invoke-RestMethod "$ragApi/answer-settings"
if (!$ragSettings.configured) { throw 'Серверный ключ DeepSeek не настроен.' }
$ragIndices=Invoke-RestMethod "$ragApi/indexes"
$ragIndex=$ragIndices | Where-Object { $_.config.strategy -eq 'STRUCTURAL' -and $_.config.maxCharacters -eq 3000 -and $_.config.overlapCharacters -eq 300 } | Select-Object -First 1
if (!$ragIndex) { throw 'Нужен STRUCTURAL индекс 3000/300.' }
$ragQuestions=Invoke-RestMethod "$ragApi/evaluation/questions"
$ragDocuments=Invoke-RestMethod "$ragApi/documents"
if ($ragQuestions.Count -ne 10) { throw 'Нужны 10 контрольных вопросов.' }
$ragReport=[ordered]@{at=[DateTime]::UtcNow.ToString('o');model=$ragSettings.model;indexId=$ragIndex.id;maxOutputTokens=$MaxOutputTokens;note='20 платных вызовов, 1200 только для теста; не автоматический judge.';cases=@()}
$ragDataDirectory=Join-Path $PSScriptRoot '../data'
New-Item -ItemType Directory -Path $ragDataDirectory -Force | Out-Null
foreach ($ragCase in $ragQuestions) {
    $ragDocInfo=$ragDocuments | Where-Object { $_.source.EndsWith('/'+$ragCase.expectedSourceSuffix) } | Select-Object -First 1
    if (!$ragDocInfo) { throw "Нет ожидаемого документа: $($ragCase.id)" }
    $ragDoc=Invoke-RestMethod "$ragApi/documents/$($ragDocInfo.id)"
    if (!$ragDoc.text.Contains($ragCase.evidenceQuote)) { throw "Опорная цитата отсутствует в snapshot: $($ragCase.id)" }
    $ragAnswers=@()
    foreach ($ragMode in @('BASELINE','RAG')) {
        $ragBody=@{question=$ragCase.question;mode=$ragMode;indexId=$ragIndex.id;topK=5;contextMaxCharacters=16000;maxOutputTokens=$MaxOutputTokens} | ConvertTo-Json
        $ragResult=Invoke-RestMethod "$ragApi/answers" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($ragBody)) -TimeoutSec 150
        if ([string]::IsNullOrWhiteSpace($ragResult.answer)) { throw "Пустой ответ: $($ragCase.id) $ragMode" }
        if (!$ragResult.usage -or $ragResult.usage.totalTokens -ne $ragResult.usage.promptTokens+$ragResult.usage.completionTokens) { throw 'Usage не подтверждён.' }
        if ($ragMode -eq 'BASELINE' -and ($ragResult.context.included.Count -ne 0 -or $ragResult.context.indexId)) { throw 'Baseline получил документы.' }
        if ($ragMode -eq 'RAG') {
            if ($ragResult.context.included.Count -eq 0 -or $ragResult.context.textCharacters -gt 16000) { throw 'Некорректный контекст RAG.' }
            $ragSent=($ragResult.messages | Where-Object role -eq 'user').content | ConvertFrom-Json
            if ($ragSent.book_context.Count -ne $ragResult.context.included.Count) { throw 'UI context не соответствует фактическому промпту.' }
        }
        $ragAnswers+=$ragResult
        "$($ragCase.id) / ${ragMode}: finish=$($ragResult.finishReason) input=$($ragResult.usage.promptTokens) output=$($ragResult.usage.completionTokens)"
    }
    if ($ragAnswers[0].model -cne $ragAnswers[1].model -or $ragAnswers[0].messages[0].content -cne $ragAnswers[1].messages[0].content) { throw 'Настройки/модель не совпали.' }
    $ragSourceHit=@($ragAnswers[1].context.included | Where-Object { $_.chunk.source.EndsWith('/'+$ragCase.expectedSourceSuffix) }).Count -gt 0
    $ragReport.cases+=@{case=$ragCase;expectedSourceIncluded=$ragSourceHit;answers=$ragAnswers}
    # Производный отчёт измерений: сохраняем после каждой пары, не исходный код и не секрет.
    [IO.File]::WriteAllText((Join-Path $ragDataDirectory 'day22-live-results.json'),($ragReport | ConvertTo-Json -Depth 40),[Text.UTF8Encoding]::new($false))
}
'Day 22 live comparison completed: data/day22-live-results.json. Качество оцените отдельно по ожиданиям.'
