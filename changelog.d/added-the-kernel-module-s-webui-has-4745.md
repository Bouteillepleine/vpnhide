_2026-08-23_

## English

The kernel module's WebUI has a Scan tab that proposes which apps are worth hiding the VPN from. Its strongest signal is not guesswork: the module already counts, per app, how many times each hook fired, so apps that have actually been caught asking about interfaces, routes or the framework's network model are listed first and ranked by how often. Apps that have never run yet are proposed from what they are, which the screen labels as the weaker signal. It also proposes what to leave alone — whatever manages the tunnel, and Play Services, whose attestation engine fingerprints the device and should not be handed a network view that contradicts its own sockets.

## Русский

В WebUI модуля ядра появилась вкладка «Scan», предлагающая, от каких приложений стоит скрыть VPN. Главный признак — не догадка: модуль уже считает по каждому приложению, сколько раз сработал каждый хук, поэтому приложения, реально замеченные за опросом интерфейсов, маршрутов или сетевой модели фреймворка, идут первыми и отсортированы по частоте. Приложения, которые ещё не запускались, предлагаются по их типу — экран прямо помечает это как более слабый признак. Также предлагается, что оставить в покое: то, что управляет туннелем, и Play Services, чей механизм аттестации снимает отпечаток устройства и не должен получать картину сети, противоречащую его же сокетам.
