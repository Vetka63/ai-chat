param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-fA-F]{16}$')][string]$NetworkId,
    [Parameter(Mandatory=$true)][string]$PhoneIp,
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskEnv = Join-Path $taskRoot '.env.day30'
$taskCli = Join-Path ${env:ProgramFiles(x86)} 'ZeroTier\One\zerotier-cli.bat'
if (-not (Test-Path -LiteralPath $taskCli)) {
    $taskCli = Join-Path $env:ProgramFiles 'ZeroTier\One\zerotier-cli.bat'
}
if (-not (Test-Path -LiteralPath $taskCli)) { throw 'Установите ZeroTier One для Windows с официального сайта.' }
$taskNetworks = & $taskCli -j listnetworks | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'Команда ZeroTier не выполнена. Нужен PowerShell от администратора.' }
$taskNetwork = @($taskNetworks | Where-Object { $_.id -eq $NetworkId.ToLowerInvariant() })
if ($taskNetwork.Count -ne 1 -or $taskNetwork[0].status -ne 'OK' -or $taskNetwork[0].type -ne 'PRIVATE') {
    throw 'Нужна подключенная PRIVATE-сеть со статусом OK. Подтвердите ПК в ZeroTier Central.'
}
$taskAddresses = @($taskNetwork[0].assignedAddresses | Where-Object { $_ -match '^\d+\.\d+\.\d+\.\d+/\d+$' })
if ($taskAddresses.Count -ne 1) { throw 'Для безопасного запуска нужна одна IPv4-подсеть ZeroTier.' }
$taskPcIp, $taskPrefix = $taskAddresses[0].Split('/')
$taskPhoneAddress = $null
if (-not [Net.IPAddress]::TryParse($PhoneIp, [ref]$taskPhoneAddress) -or $taskPhoneAddress.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { throw 'Укажите IPv4 iPhone из Managed IPs, без /маски.' }
if ($taskPhoneAddress.ToString() -eq $taskPcIp) { throw 'IP iPhone должен отличаться от IP ПК.' }
$taskPcBytes = [Net.IPAddress]::Parse($taskPcIp).GetAddressBytes()
$taskPhoneBytes = $taskPhoneAddress.GetAddressBytes()
for ($taskBit = 0; $taskBit -lt [int]$taskPrefix; $taskBit++) {
    $taskIndex = [int][math]::Floor($taskBit / 8); $taskMask = 1 -shl (7 - ($taskBit % 8))
    if (($taskPcBytes[$taskIndex] -band $taskMask) -ne ($taskPhoneBytes[$taskIndex] -band $taskMask)) { throw 'iPhone должен находиться в той же подсети ZeroTier.' }
}
$taskInterfaces = @(Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -eq $taskPcIp })
if ($taskInterfaces.Count -ne 1 -or $taskInterfaces[0].InterfaceAlias -notlike '*ZeroTier*') { throw 'IP не принадлежит единственному активному интерфейсу ZeroTier на ПК.' }
if ($CheckOnly) { Write-Host "ПК: $taskPcIp; iPhone: $PhoneIp; приватная сеть проверена. Настройки не изменены."; exit 0 }
$taskAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $taskAdmin) { throw 'Откройте PowerShell от имени администратора.' }
if (Get-NetFirewallProfile | Where-Object { -not $_.Enabled }) { throw 'Windows Firewall должен быть включён; отключённый firewall автоматически не меняется.' }
if (-not (Test-Path -LiteralPath $taskEnv)) { throw 'Сначала Initialize-Day30.ps1.' }
$taskRuleName = 'AIChallenge-Day30-ZeroTier'
$taskExisting = Get-NetFirewallRule -Name $taskRuleName -ErrorAction SilentlyContinue
if ($taskExisting) { throw 'Правило дня 30 уже существует. Для перенастройки сначала Disable-Day30ZeroTier.ps1.' }
$taskProxy = & netsh interface portproxy show v4tov4
if ($taskProxy | Where-Object { $_ -match "^\s*$([regex]::Escape($taskPcIp))\s+8583\s" }) { throw 'Listener уже существует; чужие portproxy настройки не перезаписываются.' }
$taskState = Invoke-RestMethod -Uri 'http://127.0.0.1:8583/api/v1/private/session' -TimeoutSec 10
if (-not $taskState.enabled) { throw 'Приватная защита приложения не включена; listener не создаётся.' }
# Сначала разрешаем origin в backend и ждём здоровья, лишь затем открываем VPN listener.
$taskConfig = [IO.File]::ReadAllText($taskEnv)
$taskConfig = [regex]::Replace($taskConfig, '(?m)^DAY30_ALLOWED_ORIGINS=.*$', "DAY30_ALLOWED_ORIGINS=http://127.0.0.1:8583,http://localhost:8583,http://${taskPcIp}:8583")
[IO.File]::WriteAllText($taskEnv, $taskConfig, [Text.UTF8Encoding]::new($false))
& docker compose --env-file $taskEnv -f (Join-Path $taskRoot 'compose.day30.yml') up -d --no-deps --wait --wait-timeout 180 backend
if ($LASTEXITCODE -ne 0) { throw 'Backend не готов; доступ ZeroTier НЕ включён.' }
Start-Service iphlpsvc
# Имя ZeroTier содержит [NetworkId]; NetSecurity интерпретирует скобки как wildcard.
# Точный LocalAddress уже проверен выше и достаточен вместе с RemoteAddress/LocalPort.
New-NetFirewallRule -Name $taskRuleName -DisplayName 'AI Challenge Day30 - private ZeroTier chat' -Description 'Created by Enable-Day30ZeroTier.ps1' -Direction Inbound -Action Allow -Protocol TCP -LocalAddress $taskPcIp -LocalPort 8583 -RemoteAddress $PhoneIp -Profile Any | Out-Null
& netsh interface portproxy add v4tov4 "listenaddress=$taskPcIp" listenport=8583 connectaddress=127.0.0.1 connectport=8583
if ($LASTEXITCODE -ne 0) { Remove-NetFirewallRule -Name $taskRuleName; throw 'Не удалось создать VPN listener.' }
Write-Host "На iPhone включите ZeroTier, отключите Wi-Fi и откройте http://${taskPcIp}:8583/ в Safari."
Write-Host 'Доступ ограничен адресом iPhone и паролем чата. Ollama наружу не опубликована.'
