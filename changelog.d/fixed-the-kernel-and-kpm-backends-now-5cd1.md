_2026-08-11_

## English

A target list that exceeds KernelPatch's 1024-byte control buffer is now rejected with a clear capacity error — by the activator before it is sent, and by the kernel and KPM backends on receipt — instead of being silently truncated, which could leave some selected apps unprotected.

## Русский

Список целей, не помещающийся в 1024-байтный управляющий буфер KernelPatch, теперь отклоняется с понятной ошибкой лимита — активатором до отправки и бэкендами kmod и KPM при приёме — вместо молчаливой обрезки, из-за которой часть выбранных приложений могла остаться без защиты.
