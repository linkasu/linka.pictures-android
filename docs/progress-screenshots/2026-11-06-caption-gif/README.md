# Общая подпись карточки/фразы и GIF alpha

## Адаптивность после выбора пользователя

`android21-adaptive.png` и `iphone17-adaptive.png` — оригинальные native PNG
после commit219415d: обе первые миниатюры целиком, маленькие устройства не исключены.
На Android сохранённая доска/9 карточек/фраза/Back/reopen:1/1 PASS,1.544s;
на iPhone existing-board/reopen:1/1 PASS,32.108s. Переносы, fontScale и минимум12sp
сохранены. Длинная фраза остаётся прокручиваемой; её новый native сценарий и полная
large-font/contrast/landscape/keyboard/screen-reader матрица не следуют из двух PNG.
Это не пользовательское принятие всего U1.4 и не physical/AAC proof.

Android product SHA256:
`d7a0eedaaa4c790da221c46d468f52ac7babc23331cde690448bf5b7b1988558`;
iPhone executable SHA256:
`ef1cba85d8bc96c9088c9ac1c6653e3b93bb13b1f2f6aeefc5d578d244a83a16`.
Ни production signing, ни установка на настоящую библиотеку не выполнялись.
Актуальная граница общего quality gate — в [журнале](../../development/implementation-status.md).

## История до адаптивной ширины

Оригинальные native captures на прежних заполненных синтетических досках после
caption/GIF corrections. Исходный архив не переимпортируется, фраза не очищается.
Снимки не являются автоматическим U1.4 approval или проверкой physical AAC.

Native GIF tests:5/5 на API21 и5/5 на API35, включая transparency/rectangle/frame
bounds/narrow aspect и unchanged source. Actual product API21 playback/Stop/replay
после исправлений:1/1 PASS,5.457s. Полные текущие suites:485 JVM /609 Native PASS;
lint/build gate успешен, warnings учитываются отдельно. Платформенный fontScale
не изменялся; displayed Text не переводился в bitmap и не уменьшался ниже12sp.
