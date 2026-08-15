_2026-08-15_

## English

Zygisk filesystem interface hiding now also filters raw getdents64 directory enumeration (e.g. of /sys/class/net), not just libc readdir, closing that listing path for native and Rust callers.

## Русский

Скрытие сетевых интерфейсов Zygisk на уровне файловой системы теперь фильтрует и перечисление каталогов через getdents64 (например, /sys/class/net), а не только через readdir из libc — это закрывает путь перечисления для нативных и Rust-приложений.
