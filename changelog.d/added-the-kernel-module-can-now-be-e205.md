_2026-08-22_

## English

The kernel module can now be built against a kernel that was configured and built in a separate output directory, via `--kout` / `KERNEL_OUT`. This lets a kernel builder produce the .ko against the exact tree it just built, so the module matches that kernel's vermagic and symbol CRCs instead of a generic prebuilt. The build also stops early with a clear message when the target kernel has no CONFIG_KPROBES or CONFIG_MODULES, rather than shipping a .ko that loads and hides nothing, and `--update-json none` leaves the update URL out for a module built against a custom kernel, where the upstream prebuilt would not load.

## Русский

Модуль ядра теперь можно собрать против ядра, настроенного и собранного в отдельном каталоге, через `--kout` / `KERNEL_OUT`. Это позволяет сборщику ядра получить .ko прямо против собранного дерева, так что модуль совпадает с vermagic и контрольными суммами символов этого ядра, а не берётся из общей сборки. Сборка также сразу останавливается с понятным сообщением, если в целевом ядре нет CONFIG_KPROBES или CONFIG_MODULES, вместо того чтобы выдать .ko, который загружается и ничего не скрывает, а `--update-json none` убирает ссылку на обновление для модуля, собранного под своё ядро, где готовая сборка всё равно не загрузится.
