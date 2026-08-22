_2026-08-22_

## English

The Zygisk backend no longer risks taking a hooked app down with it while filtering /proc/net. Reading one of those files reused a per-thread buffer with an unconditional borrow, so a second /proc/net open arriving on the same thread mid-filter panicked — and because the module is built to abort on panic, that ended the app it was injected into rather than just the read. Such a read now falls back to the unfiltered file, which is what the caller wanted anyway. Two smaller faults in the same path are gone too: a signal arriving mid-read truncated the file and passed the short result off as complete, and a signal mid-write failed the open outright.

## Русский

Zygisk-бэкенд больше не рискует уронить приложение, в которое внедрён, при фильтрации /proc/net. Чтение этих файлов использовало общий буфер потока с безусловным захватом, поэтому второе открытие /proc/net в том же потоке во время фильтрации вызывало панику — а так как модуль собран с прерыванием при панике, падало всё приложение, а не только чтение. Теперь такое чтение возвращает нефильтрованный файл, что и требовалось вызывающей стороне. Исправлены и две меньшие ошибки на том же пути: сигнал во время чтения обрезал файл и выдавал неполный результат за полный, а сигнал во время записи полностью проваливал открытие.
