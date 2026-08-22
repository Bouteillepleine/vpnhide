_2026-08-22_

## English

The kernel module's WebUI now also controls the two things it previously could not. Each app has a second tick for the Java layer, which the LSPosed hook picks up by re-reading this same file from system_server — so the framework network APIs can be configured without ever opening the app, only installing it. And the optional interface-file hiding for /sys/class/net and /proc/sys/net can be switched on from here; it is read when the module loads, so the WebUI says plainly when a change is still waiting on a reboot. The Status screen reports whether the Java layer is actually live this boot.

## Русский

WebUI модуля ядра теперь управляет и тем, чем раньше не мог. У каждого приложения появилась вторая отметка для Java-уровня: хук LSPosed подхватывает её, перечитывая тот же файл из system_server, поэтому сетевые API фреймворка настраиваются без единого запуска приложения — его достаточно установить. Также отсюда включается необязательное скрытие файлов интерфейса в /sys/class/net и /proc/sys/net; оно читается при загрузке модуля, поэтому WebUI прямо говорит, когда изменение ещё ждёт перезагрузки. На экране состояния видно, работает ли Java-уровень в текущей загрузке.
