_2026-08-19_

## English

KPM: resolve hook targets renamed with a Clang LTO `.llvm.<hash>` suffix — on some kernels built with Clang ThinLTO (seen on a crDroid 5.4 build) ipv6_route_seq_show was LTO-mangled, so the IPv6 /proc/net/ipv6_route hook silently didn't install and native hiding showed as partial. Now it installs.

## Русский

KPM: теперь находит целевые функции хуков, переименованные Clang LTO в `.llvm.<hash>` — на части ядер, собранных с Clang ThinLTO (замечено на сборке crDroid 5.4), ipv6_route_seq_show был так переименован, из-за чего хук на IPv6 /proc/net/ipv6_route молча не ставился и нативное скрытие было частичным. Теперь ставится.
