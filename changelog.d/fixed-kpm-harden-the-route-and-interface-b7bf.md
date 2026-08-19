_2026-08-19_

## English

KPM: harden the route and interface hooks against vendor kernels whose struct layout differs from the built-in offset table — a mismatched offset now degrades to "not hidden" instead of risking an out-of-bounds skb write or a dereference of a bogus device pointer (reported as a spontaneous reboot on a vendor 5.4 kernel).

## Русский

KPM: route- и interface-хуки теперь устойчивы к вендорским ядрам, чей layout структур отличается от встроенной таблицы смещений — неверное смещение приводит к «не скрыто» вместо риска записи за границей skb или разыменования мусорного указателя устройства (проявлялось как самопроизвольная перезагрузка на вендорском ядре 5.4).
