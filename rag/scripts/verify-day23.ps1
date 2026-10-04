param([string]$ApiUrl = 'http://localhost:8382/api/v1', [int]$MaxOutputTokens = 2400)
$ErrorActionPreference = 'Stop'
$ragApi=$ApiUrl.TrimEnd('/')
$ragSettings=Invoke-RestMethod "$ragApi/answer-settings"
if(!$ragSettings.configured) {throw 'DeepSeek key не настроен на backend.'}
$ragIndexes=Invoke-RestMethod "$ragApi/indexes"
$ragIndex=$ragIndexes | Where-Object {$_.config.strategy -eq 'STRUCTURAL' -and $_.config.maxCharacters -eq 3000 -and $_.config.overlapCharacters -eq 300} | Select-Object -First 1
if(!$ragIndex) {throw 'Нужен готовый STRUCTURAL индекс 3000/300.'}
$ragQuestions=Invoke-RestMethod "$ragApi/evaluation/questions"
if($ragQuestions.Count -ne 10) {throw 'Ожидалось 10 контрольных вопросов.'}
$ragReport=@{at=[DateTimeOffset]::UtcNow.ToString('o');indexId=$ragIndex.id;model=$ragSettings.model;maxOutputTokens=$MaxOutputTokens;note='До 40 LLM-вызовов: общий rewrite и три ответа на каждый вопрос. Output cap только для теста. Это не judge.';cases=@();negative=@()}
$ragDataDirectory=Join-Path $PSScriptRoot '../data'
New-Item -ItemType Directory -Path $ragDataDirectory -Force | Out-Null
$ragReportPath=Join-Path $ragDataDirectory "day23-live-results-$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfff'))-$([Guid]::NewGuid().ToString('N').Substring(0,8)).json"
function Save-RagReport {
    [IO.File]::WriteAllText($ragReportPath,($ragReport | ConvertTo-Json -Depth 50),[Text.UTF8Encoding]::new($false))
}
Save-RagReport
"Новый отчёт: $ragReportPath"
foreach($ragCase in $ragQuestions) {
    $ragBody=@{question=$ragCase.question;indexId=$ragIndex.id;modes=@('RAW','FILTERED','REWRITE_FILTERED');candidateTopK=10;finalTopK=5;similarityThreshold=0.65;contextMaxCharacters=16000;maxOutputTokens=$MaxOutputTokens;generateAnswers=$true} | ConvertTo-Json
    $ragResult=Invoke-RestMethod "$ragApi/experiments/compare" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($ragBody)) -TimeoutSec 240
    $ragReport.cases+=@{case=$ragCase;comparison=$ragResult}; Save-RagReport
    if($ragResult.rewriteError) {throw "Rewrite failed: $($ragCase.id) $($ragResult.rewriteError.code)"}
    if(!$ragResult.rewrite -or $ragResult.results.Count -ne 3) {throw 'Не все стадии сравнения доступны.'}
    $ragUsage=0L
    $ragUsage+=$ragResult.rewrite.usage.totalTokens
    foreach($ragMode in $ragResult.results) {
        if($ragMode.status -notin @('ANSWERED','NO_CONTEXT')) {throw "Ошибка: $($ragCase.id) $($ragMode.mode)"}
        if($ragMode.pipeline.selectedCandidates.Count -gt 5 -or $ragMode.pipeline.rawCandidates.Count -gt 10) {throw 'Нарушен top-K.'}
        if($ragMode.pipeline.filterApplied -and @($ragMode.pipeline.selectedCandidates | Where-Object {$_.similarity -lt 0.65}).Count) {throw 'Фильтр пропустил низкий score.'}
        if($ragMode.answer) {
            if($ragMode.answer.question -cne $ragCase.question) {throw 'Ответ получил rewrite вместо исходного вопроса.'}
            $ragPayload=($ragMode.answer.messages | Where-Object role -eq 'user').content | ConvertFrom-Json
            if($ragPayload.question -cne $ragCase.question -or $ragPayload.book_context.Count -ne $ragMode.answer.context.included.Count) {throw 'Промпт и UI расходятся.'}
            $ragUsage+=$ragMode.answer.usage.totalTokens
        } elseif($ragMode.generationAttempted) {throw 'Без контекста не должно быть генерации.'}
    }
    if($ragResult.totalUsage.totalTokens -ne $ragUsage) {throw 'Rewrite ошибочно учтён несколько раз либо usage расходится.'}
    $ragAnswers=@($ragResult.results | Where-Object answer)
    if(@($ragAnswers.answer.messages | Where-Object role -eq 'system' | Select-Object -ExpandProperty content -Unique).Count -ne 1) {throw 'Промпты генерации неодинаковы.'}
    if(@($ragAnswers.answer.model | Select-Object -Unique).Count -ne 1) {throw 'Модели ответов различаются.'}
    if(($ragResult.results[0].pipeline.rawCandidates.chunk.chunkId -join ',') -cne ($ragResult.results[1].pipeline.rawCandidates.chunk.chunkId -join ',')) {throw 'RAW/FILTERED не разделяют общий пул.'}
    "$($ragCase.id): $($ragResult.results.status -join ', ') · totalTokens=$($ragResult.totalUsage.totalTokens) · rewritten=$($ragResult.rewrite.query)"
}
# Нерелевантные вопросы: только RAW/FILTERED, без rewrite и платной генерации.
foreach($ragQuestion in @('Какая погода завтра в Самаре?','Как приготовить борщ?','Кто выиграет следующий чемпионат мира по футболу?')) {
    $ragBody=@{question=$ragQuestion;indexId=$ragIndex.id;modes=@('RAW','FILTERED');candidateTopK=10;finalTopK=5;similarityThreshold=0.65;contextMaxCharacters=16000;generateAnswers=$false} | ConvertTo-Json
    $ragResult=Invoke-RestMethod "$ragApi/experiments/compare" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($ragBody)) -TimeoutSec 60
    if($ragResult.llmStagesAttempted -ne 0) {throw 'Preview без rewrite не должен вызывать LLM.'}
    $ragReport.negative+=@{question=$ragQuestion;comparison=$ragResult}
    Save-RagReport
}
"Сравнение дня 23 сохранено в $ragReportPath. Качество ответов и верность rewrite оцениваются отдельно."
