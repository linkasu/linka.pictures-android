# Контрольные данные Electron UI

`python3 scripts/generate-electron-ui-fixtures.py NEW_OUTPUT_DIRECTORY` создаёт шесть
детерминированных standard pages3.0 архивов из публичной неизменной `u14/ХОЧУ.linka`:
2×3,3×3,две страницы,длинная подпись,без изображения,literal. Исходный SHA закреплён,
ZIP timestamps стабильны, hashes/порядок/геометрия находятся в `fixtures.json`.
Генератор не импортирует, не устанавливает APK и отказывается перезаписывать файлы.
Self-check: `python3 scripts/generate-electron-ui-fixtures.py --self-test`.

Общая фраза, пустая библиотека, retained archive и незавершённый draft — состояния
приложения, не новые форматы архива. Их готовят существующими проверенными UI-сценариями
в выделенном sandbox; существующую библиотеку не сбрасывают ради пустого состояния.
Existing/reopen проверки используют уже опубликованный «ХОЧУ», не импортируют повторно.
Сам генератор не доказывает импорт/runtime exchange: его проверяют native flow отдельно.

В каждом screenshot manifest обязательны commit/build hash, OS/device, viewport в
логических единицах и pixels, fontScale/textScale/theme, fixture SHA, screen state,
PNG SHA и отдельные `visually_reviewed`/`user_approved`. Неизвестные значения пишутся
как неизвестные, не подставляются из соседней сборки. Разные fixtures не образуют
доказанную пару; системный chrome и шрифтовой rasterizer рассматриваются отдельно.
