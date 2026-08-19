_2026-08-19_

## English

Port-hiding activation no longer fails when another process (netd, an OEM firewall) briefly holds the iptables lock — the activator now waits for the lock instead of aborting the whole apply.

## Русский

Активация скрытия портов больше не срывается, когда блокировку iptables на мгновение держит другой процесс (netd, фаервол прошивки) — активатор теперь ждёт освобождения блокировки, а не прерывает всю настройку.
