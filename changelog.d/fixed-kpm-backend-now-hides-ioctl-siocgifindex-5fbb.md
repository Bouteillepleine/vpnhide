_2026-08-14_

## English

KPM backend now hides ioctl(SIOCGIFINDEX) interface-index probes (used by if_nametoindex), closing a VPN-presence leak that the .ko and Zygisk backends already blocked.

## Русский

KPM-бэкенд теперь скрывает запросы индекса интерфейса через ioctl(SIOCGIFINDEX) (их использует if_nametoindex) — закрыта утечка присутствия VPN, которую бэкенды .ko и Zygisk уже блокировали.
