_2026-06-25_

## English

Hide VPN routes from native apps that read the kernel routing table directly via FORTIFY'd recvfrom/__recvfrom_chk (RTM_GETROUTE dump), closing a leak where a hidden tunnel still surfaced as an "ifNN" interface

## Русский

Скрытие маршрутов VPN от нативных приложений, читающих таблицу маршрутизации ядра напрямую через FORTIFY-обёртки recvfrom/__recvfrom_chk (дамп RTM_GETROUTE) — устранена утечка, при которой скрытый туннель всё равно проявлялся как интерфейс «ifNN»
