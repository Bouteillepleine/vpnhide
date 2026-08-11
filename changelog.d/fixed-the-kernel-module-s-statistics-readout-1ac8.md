_2026-08-11_

## English

The kernel module's statistics readout could be cut mid-record when a large target set produced more counters than its buffer holds, so the app totalled a half-read counter as if it were whole. It now ends on a complete record and marks the snapshot as partial, the same way the KPM backend already did.

## Русский

Выгрузка статистики из модуля ядра могла обрываться посреди записи, если большой набор целей давал больше счётчиков, чем помещается в буфер, — и приложение считало недочитанный счётчик за настоящий. Теперь обрыв происходит по границе записи и снимок помечается неполным, как это уже делал бэкенд KPM.
