_2026-08-22_

## English

/proc/net/route and /proc/net/ipv6_route no longer come back truncated for a hidden app when the route table is large. Removing a VPN line from a record that had just overrun the seq_file buffer cleared the kernel's "this record did not fit" signal, so the half-written record was handed to the reader as if it were complete and the iterator moved past it — the app saw a mangled line and lost every route that record would have carried. The filter now steps aside for that record and does its work on the replay the kernel performs after growing the buffer. Both the kernel module and the KernelPatch module were affected.

## Русский

/proc/net/route и /proc/net/ipv6_route больше не обрезаются у скрываемого приложения при большой таблице маршрутов. Удаление VPN-строки из записи, только что переполнившей буфер seq_file, снимало признак «запись не поместилась», поэтому недописанная запись отдавалась читателю как готовая, а итератор шёл дальше — приложение видело битую строку и теряло все маршруты этой записи. Теперь фильтр пропускает такую запись и отрабатывает на повторе, который ядро делает после увеличения буфера. Ошибка была и в модуле ядра, и в модуле KernelPatch.
