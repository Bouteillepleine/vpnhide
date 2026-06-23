_2026-06-23_

## English

LSPosed now hides VPN `Network` handles from Java Connectivity APIs: `getActiveNetwork` replaces the active VPN handle with a physical network when available, while `getAllNetworks`, `getNetworkForType(TYPE_VPN)` and `Network.writeToParcel` no longer expose the VPN network to target apps.

## Русский

LSPosed теперь скрывает VPN-дескрипторы `Network` из Java Connectivity API: `getActiveNetwork` заменяет активный VPN-дескриптор физической сетью, когда она доступна, а `getAllNetworks`, `getNetworkForType(TYPE_VPN)` и `Network.writeToParcel` больше не отдают VPN-сеть целевым приложениям.
