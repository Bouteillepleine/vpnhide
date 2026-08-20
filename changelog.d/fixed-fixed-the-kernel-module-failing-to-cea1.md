_2026-08-20_

## English

Fixed the kernel module failing to load on some OEM kernels with a trimmed module symbol table (e.g. Xiaomi HyperOS android12-5.10), where dev_get_by_index_rcu was reported as an unknown symbol; it is now resolved at runtime via kallsyms like path_put, so the module links without a hard reference.

## Русский

Исправлена загрузка модуля ядра на некоторых прошивках производителей с урезанной таблицей символов (например, Xiaomi HyperOS android12-5.10), где символ dev_get_by_index_rcu считался неизвестным: теперь он разрешается во время работы через kallsyms, как и path_put, поэтому модуль компонуется без жёсткой ссылки.
