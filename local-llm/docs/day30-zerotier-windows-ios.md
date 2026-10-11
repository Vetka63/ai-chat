# Настройка дня 30: Windows + iPhone

## 1. Подготовить приложение на ПК

Откройте PowerShell в `F:\ai-advent-challenge\ai-chat`. Ветка должна быть `ai-challenge-day-30`. Docker Desktop должен работать.

```powershell
git branch --show-current
.\local-llm\scripts\Initialize-Day30.ps1
.\local-llm\scripts\Start-Day30.ps1 -Build
```

Откройте `http://127.0.0.1:8583/`. Пароль можно посмотреть **локально** в `local-llm\.env.day30` (строка DAY30_PASSWORD, только значение после `=`). Не показывайте этот файл на видео, не отправляйте его в чат и не коммитьте. Скрипт повторной инициализации не меняет существующий пароль.

При отсутствии `qwen3:8b` загрузите веса явно:

```powershell
docker compose --env-file local-llm/.env.day30 -f local-llm/compose.day30.yml exec ollama ollama pull qwen3:8b
```

Это скачивание модели, не облачная генерация. После загрузки чат может работать без облачных моделей. ZeroTier для подключения через интернет всё равно требует сеть.

## 2. Создать приватную сеть

Установите Windows-клиент с https://www.zerotier.com/download/. Подтвердите драйвер в установщике.

Зайдите в https://my.zerotier.com/ и создайте сеть. Включите PRIVATE, не PUBLIC. Сохраните Network ID (16 hex-символов). Не добавляйте default route `0.0.0.0/0`, bridge или доступ ко всей домашней сети. Нужны только ПК и iPhone в одной виртуальной подсети, не весь интернет через VPN.

## 3. Подключить ПК

Иконка ZeroTier в трее → Join Network → Network ID. В кабинете Members/Devices найдите Node ID, совпадающий с ПК, и разрешите устройство. Назовите его home-pc. Статус клиента должен быть OK; появится Managed IP.

Административная проверка:

```powershell
& 'C:\Program Files (x86)\ZeroTier\One\zerotier-cli.bat' info
& 'C:\Program Files (x86)\ZeroTier\One\zerotier-cli.bat' listnetworks
```

Если ACCESS_DENIED — нужно подтвердить устройство; REQUESTING_CONFIGURATION — подождите/проверьте сеть.

## 4. Подключить iPhone

ZeroTier One → `+` → тот же Network ID → разрешить добавление VPN-конфигурации → включить сеть. В Central сверить Node ID телефона и подтвердить его отдельно. Назвать iphone. Посмотреть его Managed IPv4; это **не** Wi-Fi IP и не IP сотового оператора.

На время теста выключите другой VPN: конфликт двух VPN на iOS может мешать соединению. Не удаляйте чужие профили и настройки.

## 5. Открыть узкий доступ

Откройте PowerShell **от администратора**, перейдите в репозиторий. Подставьте свои Network ID и Managed IPv4 телефона:

```powershell
cd F:\ai-advent-challenge\ai-chat
.\local-llm\scripts\Enable-Day30ZeroTier.ps1 -NetworkId ВАШ_NETWORK_ID -PhoneIp IP_АЙФОНА -CheckOnly
.\local-llm\scripts\Enable-Day30ZeroTier.ps1 -NetworkId ВАШ_NETWORK_ID -PhoneIp IP_АЙФОНА
```

Первый запуск только проверяет. Второй разрешает Origin телефона, перезапускает backend (потребуется войти заново), создаёт listener только на ZeroTier IP ПК и Firewall-правило только для телефона. Порты роутера не меняются; Ollama наружу не выставляется.

Если Windows сообщает, что выполнение сценариев отключено, не меняйте LocalMachine/CurrentUser на Unrestricted. Можно запустить проверенный скрипт только в отдельном процессе:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'F:\ai-advent-challenge\ai-chat\local-llm\scripts\Enable-Day30ZeroTier.ps1' -NetworkId ВАШ_NETWORK_ID -PhoneIp IP_АЙФОНА
```

Это не сохраняет политику в реестре и не переопределяет корпоративную Group Policy. Если ограничение задано организацией, согласуйте запуск с администратором. Скрипты сохранены с UTF-8 BOM для Windows PowerShell 5.1.

## 6. Проверить сотовый интернет

Выключите Wi-Fi на iPhone, оставьте сотовую связь и ZeroTier включёнными. В Safari откройте `http://IP_ПК_ZEROTIER:8583/` — не localhost. Введите пароль приложения, создайте чат и отправьте «Назови столицу Франции одним предложением».

Затем: «Запомни: мой проект называется Листок, язык Kotlin» → «Как называется мой проект и какой язык выбран?». Ответ должен учитывать предыдущую переписку. Откройте «Токены и параметры ответа».

Выключите ZeroTier и попробуйте открыть страницу заново/обновить историю — сетевой запрос должен перестать проходить (уже загруженная страница может остаться на экране). Включите ZeroTier и обновите: сохранённый чат восстановится.

## 7. Отключение

Убрать доступ с телефона, не удаляя приложение/историю:

```powershell
.\local-llm\scripts\Disable-Day30ZeroTier.ps1
```

Остановить контейнеры без удаления volumes:

```powershell
docker compose --env-file local-llm/.env.day30 -f local-llm/compose.day30.yml stop
```

Не используйте `down -v` или Docker prune для этой задачи.

## Если iPhone не открывает страницу

1. ПК включён, не спит; Docker, клиент и backend здоровы.
2. PRIVATE/OK, оба устройства подтверждены, один Network ID.
3. В Safari используется Managed IPv4 ПК и порт 8583.
4. IPv4 телефона совпадает с Firewall-правилом. При изменении: Disable, затем Enable с новым IP.
5. Проверить `netsh interface portproxy show v4tov4` и `Get-NetTCPConnection -LocalPort 8583`.
6. Не отключайте весь Firewall. Если нужна диагностика, проверяем конкретное правило/интерфейс.

Официальные инструкции: https://docs.zerotier.com/start/, https://docs.zerotier.com/cli/, https://docs.zerotier.com/routertips/.
