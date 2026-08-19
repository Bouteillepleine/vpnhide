_2026-08-19_

## English

The app list no longer fails to load with a "couldn't read all Android profiles" error when a profile is legitimately empty — a scan that succeeds (exit 0) with no packages is now accepted, and only a profile whose scan actually errors blocks the list (seen on a Motorola vendor profile that reports zero apps).

## Русский

Список приложений больше не падает с ошибкой «не удалось прочитать все профили Android», когда профиль просто пуст — успешное сканирование (код 0) без приложений теперь принимается, и список блокирует только профиль, чьё сканирование реально завершилось ошибкой (проявлялось на вендорском профиле Motorola, отдающем ноль приложений).
