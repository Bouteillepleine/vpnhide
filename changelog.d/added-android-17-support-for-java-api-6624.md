_2026-06-23_

## English

Android 17 support for Java-API VPN hiding — the system_server hooks sanitize NetworkCapabilities through public APIs (A17 changed the private fields the old hooks read) and also scrub ConnectivityService getter results and callback bundles, covering hasTransport, getNetworkCapabilities, NetworkInfo and push callbacks

## Русский

Поддержка Android 17 для скрытия VPN через Java API — хуки в system_server чистят NetworkCapabilities через публичные API (A17 изменил приватные поля, которые читали старые хуки), а также санитизируют результаты геттеров ConnectivityService и callback-бандлы: hasTransport, getNetworkCapabilities, NetworkInfo, push-колбэки
