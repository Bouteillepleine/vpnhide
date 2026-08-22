_2026-08-22_

## English

Module zips now carry an update URL only when the build supplies one, through UPDATE_JSON_URL, and the generated update-json files point at whichever repository publishes the release. Previously the kernel-module build baked in a fixed repository, so anyone building their own modules shipped zips that advertised someone else's releases as updates — which on a custom kernel would offer a module that cannot load.

## Русский

Zip-модули теперь содержат ссылку на обновление, только если её задала сборка через UPDATE_JSON_URL, а генерируемые файлы update-json указывают на тот репозиторий, который публикует релиз. Раньше сборка модуля ядра содержала жёстко прописанный репозиторий, поэтому собранные самостоятельно модули предлагали в качестве обновлений чужие релизы — а на своём ядре такой модуль вообще не загрузится.
