param([string]$ApiUrl = 'http://localhost:8382', [switch]$BuildIndexes, [ValidateRange(0,1)][double]$MinimumDocumentHitRate = 0.8)
$ErrorActionPreference = 'Stop'
$ragApi = $ApiUrl.TrimEnd('/') + '/api/v1'
function Assert-Lab([bool]$Condition, [string]$Message) { if (!$Condition) { throw $Message } }
function Get-Lab([string]$Path) { $ragResponse = Invoke-RestMethod -Uri ($ragApi + $Path); foreach ($ragItem in $ragResponse) { Write-Output $ragItem } }
function Post-Lab([string]$Path, $Body) { Invoke-RestMethod -Uri ($ragApi + $Path) -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10))) }
$ragCorpus = Get-Lab '/corpus'
$ragDocuments = @(Get-Lab '/documents')
Assert-Lab ($ragCorpus.documentCount -eq 36 -and $ragCorpus.estimatedPages -ge 30) 'Не подтверждён объём корпуса.'
Assert-Lab ($ragDocuments.Count -eq $ragCorpus.documentCount) 'Расходится список документов.'
$ragIndexes = @()
$ragPreviews = @()
foreach ($ragStrategy in @('FIXED','STRUCTURAL')) {
    $ragConfig = @{strategy=$ragStrategy;maxCharacters=3000;overlapCharacters=300}
    $ragPreview = Post-Lab '/preview' $ragConfig
    Assert-Lab ($ragPreview.metrics.coveragePercent -eq 100 -and $ragPreview.metrics.maxCharacters -le 3000) "Некорректные диапазоны $ragStrategy."
    $ragPreviews += @{strategy=$ragStrategy;metrics=$ragPreview.metrics}
    $ragIndex = @(Get-Lab '/indexes') | Where-Object { $_.snapshotId -eq $ragCorpus.snapshotId -and $_.config.strategy -eq $ragStrategy -and $_.config.maxCharacters -eq 3000 -and $_.config.overlapCharacters -eq 300 } | Select-Object -First 1
    if (!$ragIndex -and $BuildIndexes) {
        $ragJob = Post-Lab '/jobs' $ragConfig
        $ragDeadline = [DateTime]::UtcNow.AddMinutes(20)
        do {
            Start-Sleep -Seconds 2
            $ragJob = Get-Lab ("/jobs/" + $ragJob.id)
            Write-Output "$ragStrategy $($ragJob.status) $($ragJob.processed)/$($ragJob.total)"
            Assert-Lab ([DateTime]::UtcNow -lt $ragDeadline) 'Истёк timeout проверки; job остаётся доступен в UI.'
        } while ($ragJob.status -in @('QUEUED','RUNNING'))
        Assert-Lab ($ragJob.status -eq 'READY') "Построение FAILED: $($ragJob.error)"
        $ragIndex = @(Get-Lab '/indexes') | Where-Object id -eq $ragJob.indexId | Select-Object -First 1
    }
    Assert-Lab ($null -ne $ragIndex) "Нет $ragStrategy индекса. Запустите с -BuildIndexes или постройте через UI."
    $ragChunks = @(Get-Lab ("/indexes/" + $ragIndex.id + '/chunks'))
    Assert-Lab ($ragChunks.Count -eq $ragIndex.metrics.chunkCount) 'Индекс содержит не все чанки.'
    Assert-Lab (@($ragChunks | Where-Object { !$_.source -or !$_.title -or !$_.section -or !$_.chunkId -or !$_.text }).Count -eq 0) 'Пустые тексты/метаданные.'
    $ragFirst = $ragChunks[0]
    $ragStoredDocument = Get-Lab ("/indexes/" + $ragIndex.id + '/documents/' + $ragFirst.documentId)
    Assert-Lab ($ragStoredDocument.text.Substring($ragFirst.start, $ragFirst.endExclusive - $ragFirst.start) -ceq $ragFirst.text) 'Chunk не совпадает с persisted snapshot.'
    $ragIndexes += $ragIndex
}
$ragComparison = Post-Lab '/compare' @{indexIds=@($ragIndexes.id)}
Assert-Lab $ragComparison.comparable 'Разные corpus/embedding spaces.'
$ragCases = Get-Content (Join-Path $PSScriptRoot '../evaluation/day21-search-cases.json') -Raw | ConvertFrom-Json
$ragSearches = @()
foreach ($ragIndex in $ragIndexes) {
    foreach ($ragCase in $ragCases.cases) {
        $ragResult = Post-Lab ("/indexes/" + $ragIndex.id + '/search') @{query=$ragCase.query;topK=5}
        Assert-Lab (@($ragResult.hits).Count -eq 5) 'Не получен topK=5.'
        $ragHit = @($ragResult.hits | Where-Object { $ragSource = $_.chunk.source; @($ragCase.expectedSourceSuffixes | Where-Object { $ragSource.EndsWith($_) }).Count -gt 0 }).Count -gt 0
        $ragSearches += @{strategy=$ragIndex.config.strategy;caseId=$ragCase.id;documentHitAt5=$ragHit;result=$ragResult}
        Write-Output "$($ragIndex.config.strategy) / $($ragCase.id): document_hit_at_5=$ragHit"
    }
}
$ragNegative = @()
foreach ($ragCheck in @(
    @{path='/preview';body=@{strategy='INVALID'};status=400},
    @{path='/preview';body=@{strategy='FIXED';maxCharacters=3000;overlapCharacters=2000};status=400},
    @{path='/compare';body=@{indexIds=@($ragIndexes[0].id,$ragIndexes[0].id)};status=400},
    @{path=('/indexes/'+$ragIndexes[0].id+'/search');body=@{query='';topK=5};status=400}
)) {
    $ragResponse = Invoke-WebRequest -Uri ($ragApi+$ragCheck.path) -Method Post -ContentType 'application/json' -Body ($ragCheck.body | ConvertTo-Json -Depth 8) -SkipHttpErrorCheck
    Assert-Lab ($ragResponse.StatusCode -eq $ragCheck.status) "Неверный HTTP код $($ragCheck.path): $($ragResponse.StatusCode)."
    $ragNegative += @{path=$ragCheck.path;status=$ragResponse.StatusCode;error=($ragResponse.Content | ConvertFrom-Json)}
}
$ragMissing = Invoke-WebRequest ($ragApi+'/indexes/missing/chunks') -SkipHttpErrorCheck
Assert-Lab ($ragMissing.StatusCode -eq 404) 'Неизвестный index должен возвращать 404.'
$ragQuality = @($ragSearches | Group-Object strategy | ForEach-Object {
    $hits = @($_.Group | Where-Object documentHitAt5).Count
    @{ strategy=$_.Name; hits=$hits; total=$_.Count; hitRate=$hits / $_.Count; required=$MinimumDocumentHitRate; passed=($hits / $_.Count -ge $MinimumDocumentHitRate) }
})
$ragReport = @{at=[DateTime]::UtcNow.ToString('o');kind='live-local-embedding';corpus=$ragCorpus;previews=$ragPreviews;comparison=$ragComparison;searches=$ragSearches;quality=$ragQuality;negativeChecks=$ragNegative;unknownIndexStatus=$ragMissing.StatusCode}
$ragOutputDirectory = Join-Path $PSScriptRoot '../data'
New-Item -ItemType Directory -Path $ragOutputDirectory -Force | Out-Null
# Производный отчёт измерений не является исходным кодом и исключён из Git.
$ragReportName = 'live-verification-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff') + '.json'
[System.IO.File]::WriteAllText((Join-Path $ragOutputDirectory $ragReportName), ($ragReport | ConvertTo-Json -Depth 30), [System.Text.UTF8Encoding]::new($false))
Assert-Lab (@($ragQuality | Where-Object { !$_.passed }).Count -eq 0) "Retrieval quality gate FAILED; report: rag/data/$ragReportName"
Write-Output "Technical checks and retrieval quality gate passed; report: rag/data/$ragReportName"
