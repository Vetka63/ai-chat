#requires -Version 7.0
param([Parameter(Mandatory)][string[]]$ChatIds, [switch]$RestoreDirectApi)
$ErrorActionPreference = 'Stop'
$budget = Invoke-RestMethod http://localhost:8787/budget -TimeoutSec 10
if ($budget.pending -ne 0) { throw 'Есть платные запросы в работе; restart запрещён.' }
$before = @{}
function Get-ChatProof([string]$Id) {
    $detail = Invoke-RestMethod "http://localhost:8382/api/v1/conversations/$Id" -TimeoutSec 20
    if (@($detail.turns | Where-Object status -eq PENDING).Count) { throw "В чате $Id есть PENDING." }
    $payload = [ordered]@{ conversation = $detail.conversation; memory = $detail.memory; turns = $detail.turns } | ConvertTo-Json -Depth 100 -Compress
    @{ id = $Id; turns = $detail.turns.Count; hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($payload))) }
}
foreach ($id in $ChatIds) { $parsed = [Guid]::Empty; if (![Guid]::TryParse($id, [ref]$parsed)) { throw 'Некорректный ID.' }; $before[$id] = Get-ChatProof $id }
$path = Join-Path $PSScriptRoot "../data/video-restart-$([DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfff')).json"
$proof = @{ at = [DateTimeOffset]::UtcNow.ToString('o'); before = $before; after = @{}; status = 'PREPARED'; note = 'GET snapshot hashes before/after actual backend restart. No LLM calls. Continuation must be verified separately.' }
function Save-Proof { [IO.File]::WriteAllText($path, ($proof | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false)) }
Save-Proof
if ($RestoreDirectApi) {
    $proof.note += ' Backend is recreated with normal direct provider URL after budgeted testing; volumes are retained.'
    docker compose --env-file ../.env -f compose.yml -f compose.gpu.yml up -d backend client
} else {
    docker compose --env-file ../.env -f compose.yml -f compose.gpu.yml restart backend
}
if ($LASTEXITCODE -ne 0) { throw 'Restart failed; proof retained.' }
$ready = $false
for ($attempt = 0; $attempt -lt 10; $attempt++) {
    try { $health = Invoke-RestMethod http://localhost:8382/actuator/health -TimeoutSec 5; if ($health.status -eq 'UP') { $ready = $true; break } } catch { }
    Start-Sleep -Seconds 1
}
if (!$ready) { $proof.status = 'READINESS_NOT_CONFIRMED'; Save-Proof; throw 'Readiness not confirmed in time; proof retained. Do not claim a hash match.' }
foreach ($id in $ChatIds) { $proof.after[$id] = Get-ChatProof $id }
$proof.status = if (@($ChatIds | Where-Object { $proof.before[$_].hash -cne $proof.after[$_].hash }).Count) { 'MISMATCH' } else { 'MATCH' }
Save-Proof
"Restart proof: $($proof.status); $path"
if ($proof.status -ne 'MATCH') { throw 'State changed across restart; inspect proof.' }
