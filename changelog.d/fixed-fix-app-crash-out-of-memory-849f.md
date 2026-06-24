_2026-06-24_

## English

Fix app crash (out-of-memory abort) when running diagnostics with a VPN connected on some kernels — the native netlink interface/route checks now bound their read loop and time out instead of looping until the allocator aborts

## Русский

Исправлен вылет приложения (аварийное завершение из-за нехватки памяти) при запуске диагностики с подключённым VPN на некоторых ядрах — нативные проверки интерфейсов/маршрутов через netlink теперь ограничивают цикл чтения и завершаются по таймауту вместо зацикливания до аварии аллокатора
