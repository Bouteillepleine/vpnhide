_2026-08-22_

## English

A new mode hides the VPN from every app at once, instead of only the ones picked one by one. Turn it on in the kernel module's WebUI and the app list inverts: what you tick is now what stays able to see the VPN, which is where your VPN client belongs. System components are never affected either way — the kernel refuses to target anything below the first app UID. The Zygisk backend keeps working as before; it hooks processes it was told about by UID, so hiding from everything is not something it can express.

## Русский

Новый режим скрывает VPN сразу от всех приложений, а не только от выбранных по одному. Включите его в WebUI модуля ядра, и список приложений инвертируется: отмеченные теперь остаются теми, кто видит VPN, — туда и относится ваш VPN-клиент. Системные компоненты не затрагиваются ни в одном режиме: ядро не берёт в цели ничего ниже первого UID приложений. Бэкенд Zygisk работает как раньше — он ставит хуки в процессы по заранее известным UID, поэтому «скрывать от всего» он выразить не может.
