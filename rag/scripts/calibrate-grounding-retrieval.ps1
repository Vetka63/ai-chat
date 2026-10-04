param([string]$ApiUrl = 'http://localhost:8382/api/v1')
$ErrorActionPreference = 'Stop'
$api = $ApiUrl.TrimEnd('/')
$indexes = Invoke-RestMethod "$api/indexes"
$index = $indexes | Where-Object { $_.config.strategy -eq 'STRUCTURAL' -and $_.config.maxCharacters -eq 3000 -and $_.config.overlapCharacters -eq 300 } | Select-Object -First 1
if (!$index) { throw 'Нужен STRUCTURAL индекс 3000/300.' }
$cases = Invoke-RestMethod "$api/evaluation/questions"
$cases += @(
    @{id='delete-merged'; question='Как удалить локальную feature-login после успешного слияния?'; expectedSourceSuffix='branch-management.asc'; evidenceQuote='git branch -d'},
    @{id='delete-merged-resolved'; question='Как в Git удалить локальную ветку после успешного слияния?'; expectedSourceSuffix='basic-branching-and-merging.asc'; evidenceQuote='git branch -d'},
    @{id='conflict'; question='Две ветки меняли одну строку. Как выглядит конфликт слияния?'; expectedSourceSuffix='basic-branching-and-merging.asc'; evidenceQuote='конфликт'},
    @{id='weather'; question='Какая погода завтра в Самаре?'; expectedSourceSuffix=$null},
    @{id='recipe'; question='Как приготовить борщ?'; expectedSourceSuffix=$null},
    @{id='football'; question='Кто выиграет следующий чемпионат мира по футболу?'; expectedSourceSuffix=$null}
)
$run = [DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfff')
$path = Join-Path $PSScriptRoot "../data/retrieval-calibration-$run.json"
$report = @{at=[DateTimeOffset]::UtcNow.ToString('o'); indexId=$index.id; llmCalls=0; note='Только локальные embeddings. Recall означает наличие ожидаемого фрагмента среди finalK, а не полноту ответа или вероятность истины.'; cases=@(); thresholds=@()}
foreach ($case in $cases) {
    $body = @{query=$case.question; topK=20} | ConvertTo-Json
    $found = Invoke-RestMethod "$api/indexes/$($index.id)/search" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 180
    $report.cases += @{case=$case; retrieval=$found}
    [IO.File]::WriteAllText($path, ($report | ConvertTo-Json -Depth 30), [Text.UTF8Encoding]::new($false))
}
foreach ($finalK in @(5, 10)) { foreach ($threshold in @(.55, .60, .65, .70)) {
    $positive = 0; $negative = 0; $missed = @()
    foreach ($entry in $report.cases) {
        $selected = @($entry.retrieval.hits | Where-Object { $_.similarity -ge $threshold } | Select-Object -First $finalK)
        if (!$entry.case.expectedSourceSuffix) { if (!$selected.Count) { $negative++ }; continue }
        $expected = @($selected | Where-Object { $_.chunk.source.EndsWith($entry.case.expectedSourceSuffix) -and $_.chunk.text.Contains($entry.case.evidenceQuote) })
        if ($expected.Count) { $positive++ } else { $missed += $entry.case.id }
    }
    $row = @{threshold=$threshold; finalK=$finalK; positiveEvidenceRecall=$positive; positives=13; emptyNegatives=$negative; negatives=3; missed=$missed}
    $report.thresholds += $row
    [pscustomobject]$row | ConvertTo-Json -Compress
} }
[IO.File]::WriteAllText($path, ($report | ConvertTo-Json -Depth 30), [Text.UTF8Encoding]::new($false))
"Trace: $path; DeepSeek calls: 0"
