_2026-06-23_

## English

Hide the VPN from apps that detect it only through network callbacks (e.g. VTB) — on Android 13+ the ConnectivityService callback hook now installs against the Connectivity APEX classloader instead of silently failing, so pushed NetworkCapabilities are sanitized for target apps just like synchronous queries

## Русский

Скрытие VPN от приложений, которые палят его только через сетевые колбэки (например, ВТБ): на Android 13+ хук колбэков ConnectivityService теперь ставится через classloader Connectivity APEX, а не молча падает — push-данные о сети санитизируются для целевых приложений так же, как синхронные запросы
