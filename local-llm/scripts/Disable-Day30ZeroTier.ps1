param()
$ErrorActionPreference = 'Stop'
$taskRule = Get-NetFirewallRule -Name 'AIChallenge-Day30-ZeroTier' -ErrorAction SilentlyContinue
if (-not $taskRule) { Write-Host 'Правило дня 30 не найдено. Ничего не изменено.'; exit 0 }
if ($taskRule.Description -ne 'Created by Enable-Day30ZeroTier.ps1') { throw 'Правило не принадлежит скрипту дня 30.' }
$taskTarget = ($taskRule | Get-NetFirewallAddressFilter).LocalAddress
if (@($taskTarget).Count -ne 1) { throw 'Неоднозначный адрес listener; проверьте вручную.' }
& netsh interface portproxy delete v4tov4 "listenaddress=$taskTarget" listenport=8583
if ($LASTEXITCODE -ne 0) { throw 'Не удалось удалить listener. Firewall-правило оставлено для диагностики.' }
Remove-NetFirewallRule -Name 'AIChallenge-Day30-ZeroTier'
Write-Host 'Удалены только VPN listener 8583 и правило, созданные для дня 30. Контейнеры и данные сохранены.'
