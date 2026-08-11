_2026-08-11_

## English

The kernel and KPM backends now reject a truncated or over-capacity target list instead of silently applying part of it. The KPM's config transport caps a payload at 1024 bytes and cut it off without a word, so a large enough app selection could quietly leave some apps unprotected.

## Русский

Бэкенды kmod и KPM теперь отвергают обрезанный или не помещающийся список целей вместо того, чтобы молча применить его часть. Транспорт конфига у KPM обрезает всё длиннее 1024 байт без единого сообщения, так что при достаточно большом наборе приложений часть из них могла тихо остаться без защиты.
