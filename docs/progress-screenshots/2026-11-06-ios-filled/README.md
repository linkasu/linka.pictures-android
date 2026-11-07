# iOS17: настоящий Files import и сохранённая фраза

Только owned iOS17.0 / `linka_platform_probe_ios17`, development bundle
`su.linka.pictures.development`, штатная отдельно разрешённая Simulator-подпись.
Исходный архив ХОЧУ.linka SHA
`a0589a029f77bfff7307d6ee3c4dbd41e1b2badd52ca90896ca5c835ccfb293b`.

Данные реально выбраны через Files из отдельного test-host Documents и прошли
native inspection/preview, явные unlock/ImportNew и публикацию. Использовано
штатное имя «Импортированная доска»: ввод/переименование не подменяется этим
сценарием и проверяется отдельно. Production file-sharing/данные не включались.

Первый import macro дал RED уже после публикации при закрытии меню режима;
это не переименовано в end-to-end PASS. Повторного импорта не было. Отдельный
`testComposeExistingOwnedBoardAndReopen` прошёл **1/1,37.200s**:9 реальных card buttons,
ХОЧУ→КУШАТЬ без displayed autoplay, возврат в библиотеку и сохранённая фраза после
повторного открытия. PNG — оригинальные XCTest attachments, просмотрены primary.

U1.4 остаётся OPEN: default title отличается от baseline, на portrait видны
разрывы слов; audio/physical/large-font/multi-window acceptance не следует из
визуального состояния и данного UI-теста. Пользователь эти кадры ещё не принимал.
