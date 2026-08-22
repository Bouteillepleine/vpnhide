_2026-08-22_

## English

Configuring the kernel module without installing the app works. The activator waited for PackageManager to list the app's own package before it would resolve anything, so on a device set up from the module's WebUI alone it burned its whole timeout and then failed every apply — the module stayed loaded and hooked, and hid nothing. It now waits for PackageManager to answer with real data and for the packages the configuration actually targets, which does not depend on the app being installed. A configured package that is not installed no longer fails the whole apply either; the remaining targets are applied.

## Русский

Настройка модуля ядра без установки приложения теперь работает. Активатор ждал, пока PackageManager покажет пакет самого приложения, поэтому на устройстве, настроенном только через WebUI модуля, он вырабатывал весь таймаут и затем проваливал любое применение — модуль оставался загруженным и с хуками, но ничего не скрывал. Теперь он ждёт, пока PackageManager начнёт отвечать реальными данными и покажет пакеты, которые указаны в конфигурации, и это не зависит от установленного приложения. Указанный, но не установленный пакет больше не проваливает применение целиком — остальные цели применяются.
