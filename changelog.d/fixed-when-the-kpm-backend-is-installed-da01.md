_2026-08-19_

## English

When the KPM backend is installed but the kernel has no live KernelPatch runtime to load it (detected via `kpatch hello`, the same check KPatch-Next uses), the dashboard now tells you to install the KPatch-Next-Module and patch your kernel from its interface — or use APatch/FolkPatch — instead of a cryptic "kpatch CLI not found — reinstall the zip" error.

## Русский

Когда KPM установлен, но у ядра нет живого runtime KernelPatch для его загрузки (определяется через `kpatch hello` — так же, как это делает KPatch-Next), на обзоре теперь предлагается установить KPatch-Next-Module и пропатчить ядро в его интерфейсе — или использовать APatch/FolkPatch — вместо непонятной ошибки «kpatch CLI not found — переустановите zip».
