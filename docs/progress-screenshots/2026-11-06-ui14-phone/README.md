# API21: сохранённая заполненная development-доска

Только owned `linka_platform_probe_api21_arm64` / `emulator-5554`, API21 arm64,
portrait1080×1920,density480/effective fontScale1.0. Без wipe/reset/snapshot и без
повторного импорта после публикации. Источник — неизменённая публичная ХОЧУ.linka.

Исходный incoming macro действительно прошёл publish/9 AX cards/ХОЧУ КУШАТЬ,
но завершился **RED на ActivityScenario cleanup** из-за изменённого Intent.
Это не переименовано в end-to-end PASS. После test-only исправления Intent cleanup
отдельные warm-negative/close и reopen/native Back дали **2/2 PASS,2.408s**.
Холодный запуск после replace-only обновления APK прочитал опубликованную доску
и сообщение. SAF/cross-UID grant, positive audio/mic и полная phone/font/AAC матрица
не оцениваются этим набором.

PNG признаются evidence только после фактического capture/read. В отличие от
одобренной ранее tablet-пары, новые phone кадры ещё не приняты пользователем.

Просмотрены `library.png` и `compose-filled.png`: 9 карточек видимы в portrait,
сообщение сохранено. Это **не visual PASS**: видны неудобные разрывы длинных слов,
а у нижних GIF — чёрная подложка. Source archive не менялся; чтение ZIP подтвердило
индексированную прозрачность GIF89a (нижние3:30/15/8 frames,transparent index0).
Импорт сохраняет исходные bytes/SHA и не flatten'ит их. Различие API21 BitmapFactory
и API35 ImageDecoder — гипотеза, native stored-alpha/render и API21 baseline1.4
ещё не сравнивались. Это нельзя называть доказанной новой регрессией без проверки.
