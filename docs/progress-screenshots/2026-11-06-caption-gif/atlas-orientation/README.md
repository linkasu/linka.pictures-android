# Поворот: AX-проверки отдельно от пикселей

Первый read-only сценарий existing library → portrait board → library → landscape
board → library прошёл на iPhone17 (1/1,39.568s) и iPad17 (1/1,38.389s). Девять
карточек были доступны через native AX, общая фраза осталась ХОЧУ КУШАТЬ. После
сценария ориентация возвращена в portrait, библиотеки не очищались/не импортировались.

**Визуальная приёмка OPEN:** app-scoped landscape captures показывают чёрные
области/обрезание. Это не скрыто фактом AX PASS. Оригинальные восемь PNG сохранены
без поворота, crop или ретуши; SHA и xcresult в `app-capture-manifest.json`.
Для отделения XCTest capture от layout используется отдельная проверка полного
`XCUIScreen.main.screenshot()` с геометрией. Пока она не разобрана, причину нельзя
объявлять ни дефектом приложения, ни только ошибкой screenshot API.

Это не полный UI atlas, user approval, screen-reader или physical/AAC proof.

## Full-screen проверка и реальный дефект короткой фразы

`full-screen-capture-manifest.json` сохраняет оригинальные full native PNG и
геометрию XCUIScreen. iPhone1/1 (38.684s), iPad1/1 (35.051s) подтвердили обе
ориентации/9 AX карточек и прежнюю фразу. Полные кадры не имеют чёрной области
app-scoped iPhone capture; iPad raw PNG нужно читать с записанной native
UIImage orientation, не считать повёрнутый raster доказательством сломанного UI.

На настоящем iPhone landscape был другой дефект: короткие квадраты миниатюр
обрезали ХОЧУ/КУШАТЬ. После preferred width96dp, по-прежнему capped половиной
viewport, native orientation1/1 (35.052s) и просмотр полного кадра подтвердили
читаемые подписи. Изображения над ними ещё слишком малы: последующая min-height64
коррекция здесь **не засчитана**. Исторические проблемные PNG не заменялись.

## Актуально: height64 проверена отдельно

`height64-native-manifest.json`: полный505 JVM/629 Native gate, iPhone1/1
(35.866s), iPad1/1 (33.346s), API21 landscape1/1 (2.485s). Во всех сценариях
девять карточек и прежняя фраза сохранены. Новые оригинальные full-screen кадры
`phone-height64-*`, `ipad-height64-*` и
`android21-landscape-height64-stable.png` просмотрены. Подписи не обрезаются,
картинки получают место над ними; fontScale/min12sp и геометрия основных карточек
не подменялись. Физическая различимость остаётся частью отдельной AAC-приёмки.

`android21-landscape-height64.png` — сохранённый **неудачный animation frame**,
не итоговый UI. Стабильный кадр получен по pixel-stability criterion250ms с
deadline20s, без fixed sleep/изменения системных анимаций. Старый файл не ретуширован
и не заменён. Для iPad сохраняется исходная native UIImage orientation в manifest.
Весь U1.4, large-font/contrast/keyboard/screen-reader и user approval остаются OPEN.
