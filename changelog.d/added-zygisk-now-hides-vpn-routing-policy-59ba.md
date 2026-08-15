_2026-08-15_

## English

Zygisk now hides VPN routing policy rules (ip rule / RTM_GETRULE) from target apps — a detection vector that previously only the kernel backends (.ko / KPM) covered, so Zygisk-only devices leaked it.

## Русский

Zygisk теперь скрывает правила маршрутизации VPN (ip rule / RTM_GETRULE) от целевых приложений — этот вектор раньше закрывали только модули уровня ядра (.ko / KPM), поэтому на устройствах только с Zygisk он утекал.
