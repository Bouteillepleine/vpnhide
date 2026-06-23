_2026-06-23_

## English

Restore the VPN to Wi-Fi disguise for the legacy NetworkInfo API (getActiveNetworkInfo): the hook copied NetworkInfo's connection-state enum fields with an int accessor and threw on every call, so target apps could still see a VPN NetworkInfo

## Русский

Восстановлен дизгайз VPN под Wi-Fi для устаревшего API NetworkInfo (getActiveNetworkInfo): хук копировал enum-поля состояния через int-аксессор и падал на каждом вызове, из-за чего целевые приложения могли видеть VPN-NetworkInfo
