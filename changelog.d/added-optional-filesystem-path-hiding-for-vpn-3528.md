_2026-08-19_

## English

Optional filesystem path hiding for VPN interfaces under sysfs and /proc/sys/net, on all three native backends. kmod and KPM apply it after a reboot and install no VFS hooks at all while it is off; Zygisk covers it best-effort in-process, filtering both libc readdir and raw getdents64 so native and Rust callers cannot enumerate those directories either.

## Русский

Опциональное скрытие путей VPN-интерфейсов в sysfs и /proc/sys/net — на всех трёх нативных бэкендах. В kmod и KPM оно применяется после перезагрузки, а пока выключено, VFS-хуки не ставятся вовсе; Zygisk закрывает это best-effort внутри процесса, фильтруя и readdir из libc, и сырой getdents64, так что перечислить эти каталоги не смогут ни нативные, ни Rust-приложения.
