_2026-08-13_

## English

Zygisk native mode now works in 32-bit app processes: the module ships an armeabi-v7a build alongside arm64-v8a (previously NeoZygisk failed to load it into processes forked from zygote32, e.g. on Samsung devices).

## Русский

Нативный режим Zygisk теперь работает в 32-битных процессах приложений: модуль включает сборку armeabi-v7a наряду с arm64-v8a (раньше NeoZygisk не мог загрузить его в процессы, форкнутые из zygote32, например на устройствах Samsung).
