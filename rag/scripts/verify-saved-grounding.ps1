param([Parameter(Mandatory)][string[]]$ReportPaths, [string]$SnapshotApiUrl = '')
# Без параметра SnapshotApiUrl — полностью локально. С ним — только GET сохранённых
# snapshot на localhost, никогда LLM. Не доказывает смысл и полноту утверждений.
$ErrorActionPreference = 'Stop'
$snapshots = @{}
if ($SnapshotApiUrl) {
    $snapshotUri = [Uri]$SnapshotApiUrl
    if (!$snapshotUri.IsLoopback -or $snapshotUri.Scheme -notin @('http','https')) { throw 'Для проверки snapshot разрешён только локальный API.' }
}
$checked = 0; $answered = 0; $quotes = 0; $turns = @{}
$rows = @()
foreach ($path in $ReportPaths) {
    $report = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    foreach ($case in @($report.cases) + @($report.negative)) {
        if ($case.result) { $rows += @{ result = $case.result; turn = $null; file = $path } }
    }
    foreach ($record in $report.results) {
        if ($record.turn) {
            $turns[$record.turn.id] = @{ turn = $record.turn; chat = $record.conversationId }
            if ($record.turn.result) { $rows += @{ result = $record.turn.result; turn = $record.turn; chat = $record.conversationId; file = $path } }
        }
    }
}
if (!$rows.Count) { throw 'Не найдены GroundedResult. Нужны reports приложения, а не отдельного judge.' }
foreach ($row in $rows) {
    $r = $row.result
    $generations = @($r.rewrite, $r.repair.originalGeneration, $r.generation,
        $r.repair.originalSupportCheck.generation, $r.supportCheck.generation) +
        @($r.repair.originalSupportCheck.additionalGenerations) + @($r.supportCheck.additionalGenerations)
    $generations = @($generations | Where-Object { $null -ne $_ })
    if ($generations.Count -gt $r.llmStagesAttempted) { throw 'Стадий больше, чем попыток.' }
    if ($r.totalUsage) {
        if ($generations.Count -ne $r.llmStagesAttempted -or @($generations | Where-Object { !$_.usage }).Count) { throw 'Суммарный usage известен при неполных измерениях.' }
        foreach ($metric in @('promptTokens','completionTokens','totalTokens')) {
            if ($r.totalUsage.$metric -ne ($generations | ForEach-Object { $_.usage.$metric } | Measure-Object -Sum).Sum) { throw "Неверный usage: $metric" }
        }
    }
    if ($r.status -eq 'ANSWERED') {
        $answered++
        if (!$r.claims.Count -or !$r.sources.Count -or $r.supportCheck.status -ne 'PASSED') { throw 'ANSWERED без доказательств/вердикта.' }
        $indices = @($r.supportCheck.claims.claimIndex | Sort-Object)
        if (($indices -join ',') -cne ((0..($r.claims.Count - 1)) -join ',') -or @($r.supportCheck.claims | Where-Object verdict -ne 'SUPPORTED').Count) { throw 'Проверка не покрывает все пункты.' }
        foreach ($claim in $r.claims) {
            if (!$claim.citations.Count) { throw 'Пункт без цитат.' }
            foreach ($c in $claim.citations) {
                $hits = @($r.retrieval.included | Where-Object { $_.chunk.chunkId -ceq $c.source.chunkId })
                if ($hits.Count -ne 1) { throw 'Цитата не из фактически переданного контекста.' }
                $chunk = $hits[0].chunk
                if ($c.startInChunk -lt 0 -or $c.endInChunkExclusive -gt $chunk.text.Length -or $c.endInChunkExclusive -le $c.startInChunk) { throw 'Неверный диапазон цитаты.' }
                if ($chunk.text.Substring($c.startInChunk, $c.endInChunkExclusive - $c.startInChunk) -cne $c.quote) { throw 'Цитата изменена.' }
                if ($c.canonicalStart -ne $chunk.start + $c.startInChunk -or $c.canonicalEndExclusive -ne $chunk.start + $c.endInChunkExclusive) { throw 'Неверные координаты snapshot.' }
                foreach ($field in @('documentId','source','title','section')) {
                    if ($c.source.$field -cne $chunk.$field) { throw "Metadata не от исходного чанка: $field" }
                }
                if ($SnapshotApiUrl) {
                    $key = $r.request.indexId + ':' + $c.source.documentId
                    if (!$snapshots.ContainsKey($key)) {
                        $index = [Uri]::EscapeDataString($r.request.indexId)
                        $document = [Uri]::EscapeDataString($c.source.documentId)
                        $snapshots[$key] = Invoke-RestMethod "$($SnapshotApiUrl.TrimEnd('/'))/indexes/$index/documents/$document" -TimeoutSec 15
                    }
                    if ($snapshots[$key].text.Substring($c.canonicalStart, $c.canonicalEndExclusive - $c.canonicalStart) -cne $c.quote) { throw 'Цитата не совпадает с полным snapshot.' }
                }
                $quotes++
            }
        }
    } elseif ($r.claims.Count -or $r.sources.Count) { throw 'Отклонённый результат публикует claims/sources.' }
    if ($row.turn) {
        $t = $row.turn
        if ($t.preparation.issues.Count -eq 0 -and $r.retrieval -and $r.retrieval.searchQuery -cne $t.preparation.query) { throw 'Поиск не использовал contextual query.' }
        foreach ($fact in $t.memoryAfter.facts) {
            $origin = $turns[$fact.sourceTurnId]
            if (!$origin -or $origin.chat -cne $row.chat -or [string]::IsNullOrWhiteSpace($fact.quote) -or !$origin.turn.question.Contains($fact.quote)) { throw 'Нет пользовательского происхождения памяти в том же чате; передайте полный сохранённый report.' }
        }
        if ($t.llmStagesAttempted -ne 1 + $r.llmStagesAttempted) { throw 'Подготовка не учтена отдельной стадией.' }
        if ($t.totalUsage -and $t.totalUsage.totalTokens -ne $t.preparation.usage.totalTokens + $r.totalUsage.totalTokens) { throw 'Неверный суммарный usage чата.' }
    }
    $checked++
}
[pscustomobject]@{ reports = $ReportPaths.Count; results = $checked; answered = $answered; exactQuotes = $quotes; llmCalls = 0; snapshotGetRequests = $snapshots.Count; semanticQuality = 'NOT_EVALUATED'; note = 'Проверены структура, диапазоны цитат, metadata, учёт стадий и provenance памяти. Полный snapshot сверяется только при SnapshotApiUrl; смысл и полнота утверждений здесь не проверяются.' } | ConvertTo-Json
