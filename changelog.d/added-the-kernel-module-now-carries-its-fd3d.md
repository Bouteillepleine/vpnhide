_2026-08-22_

## English

The kernel module now carries its own WebUI, so it can be configured from a root manager without installing the app at all. Open the module in KernelSU (or any manager with WebUI support) to see whether it loaded, pick which apps the VPN is hidden from, watch per-app interception counts, and read the raw control node. It writes the same configuration file the app does and merges into it rather than replacing it, so a setup made in the app keeps its Java-layer and port selections. The app is still what covers Android's Java network APIs; the WebUI covers the kernel level only.

## Русский

Модуль ядра теперь содержит собственный WebUI, поэтому его можно настроить прямо из менеджера root, вообще не устанавливая приложение. Откройте модуль в KernelSU (или другом менеджере с поддержкой WebUI), чтобы увидеть, загрузился ли он, выбрать приложения, от которых скрывается VPN, посмотреть счётчики перехватов по приложениям и прочитать управляющий узел. WebUI пишет тот же файл конфигурации, что и приложение, и дополняет его, а не заменяет, поэтому настройки Java-уровня и портов, сделанные в приложении, сохраняются. Java-сетевые API Android по-прежнему закрывает только приложение; WebUI отвечает за уровень ядра.
