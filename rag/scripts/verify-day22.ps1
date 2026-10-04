param([string]$ApiUrl='http://localhost:8382', [int]$MaxOutputTokens=2400)
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
$ragReport=[ordered]@{at=[DateTime]::UtcNow.ToString('o');model=$ragSettings.model;indexId=$ragIndex.id;maxOutputTokens=$MaxOutputTokens;note='20 платных вызовов; output cap только для теста, не default приложения; не автоматический judge.';cases=@()}
$ragDataDirectory=Join-Path $PSScriptRoot '../data'
New-Item -ItemType Directory -Path $ragDataDirectory -Force | Out-Null
$ragReportPath=Join-Path $ragDataDirectory "day22-live-results-$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfff'))-$([Guid]::NewGuid().ToString('N').Substring(0,8)).json"
function Save-RagReport { [IO.File]::WriteAllText($ragReportPath,($ragReport | ConvertTo-Json -Depth 40),[Text.UTF8Encoding]::new($false)) }
Save-RagReport
"Новый отчёт: $ragReportPath"
foreach ($ragCase in $ragQuestions) {
    $ragDocInfo=$ragDocuments | Where-Object { $_.source.EndsWith('/'+$ragCase.expectedSourceSuffix) } | Select-Object -First 1
    if (!$ragDocInfo) { throw "Нет ожидаемого документа: $($ragCase.id)" }
    $ragDoc=Invoke-RestMethod "$ragApi/documents/$($ragDocInfo.id)"
    if (!$ragDoc.text.Contains($ragCase.evidenceQuote)) { throw "Опорная цитата отсутствует в snapshot: $($ragCase.id)" }
    $ragAnswers=@()
    $ragEntry=@{case=$ragCase;expectedSourceIncluded=$null;answers=@()}
    $ragReport.cases+=$ragEntry
    foreach ($ragMode in @('BASELINE','RAG')) {
        $ragBody=@{question=$ragCase.question;mode=$ragMode;indexId=$ragIndex.id;topK=5;contextMaxCharacters=16000;maxOutputTokens=$MaxOutputTokens} | ConvertTo-Json
        $ragResult=Invoke-RestMethod "$ragApi/answers" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($ragBody)) -TimeoutSec 150
        $ragEntry.answers+= $ragResult; Save-RagReport
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
    $ragEntry.expectedSourceIncluded=$ragSourceHit
    # Производный отчёт измерений: сохраняем после каждой пары, не исходный код и не секрет.
    Save-RagReport
}
"Day 22 live comparison completed: $ragReportPath. Качество оцените отдельно по ожиданиям."
