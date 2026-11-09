# Настройки: native сохранение отдельно от визуальной приёмки

На owned iPhone17 выполнены три отдельные попытки без замены библиотеки:

1. Read-only Settings/scale dialog:1/1 PASS,23.115s. Исходные scale1.0 и contrastOff.
2. Ввод2 и включение контраста сохранены первым Save. Macro остался **RED,60.837s**:
   ошибочно ожидал обязательный дополнительный prompt после уже принятого действия.
   Auth-код не менялся; тест теперь следует только реально показанному запросу.
3. Resume-only: канонические2.0/contrastOn перечитаны после запуска → доска →
   исходные1.0/contrastOff восстановлены через обычный UI → cold reopen:
   **1/1 PASS,102.725s**. Фраза ХОЧУ КУШАТЬ и9 карточек сохранены.

`settings-original-evidence.json` и оригинальные PNG/TXT сохраняют все результаты,
включая RED. Архив не содержит backup, SQL, credentials или app data seeding.
Системный fontScale, голоса, сеть и громкость не менялись. Значение Switch.value
не использовалось как единственный proof: native Switch.isSelected подтвердил
включённый и выключенный контраст в перечитанных настройках.

**Visual RED:** при portable scale2.0 обрезались заголовок доски и некоторые
длинные подписи; карточка могла потерять область изображения. Это не закрыто
фактом native AX/persistence PASS. Общая подпись получает стандартную прокрутку,
изображению сохраняется viewport, все пять toolbar callers получают scaled height
и прокручиваемое полное название. Новые native кадры ещё проверяются отдельно.
Принятый пользователем normal portrait/landscape срез не означает принятие2.0,
всего U1.4, system × portable scale, VoiceOver/TalkBack или physical/AAC.

## Последующие подтверждения

`large-font-native-fixed-evidence.json`: iPhone1/1,152.591s с точным попаданием
жестов. Просмотрены начало/конец подписи КАКАТЬ и заголовка, картинка сохранена,
фраза/audio state не изменились; исходные настройки восстановлены.
Первый procedural PASS148.136s не засчитан как proof прокрутки: жест попадал в
родительский контейнер/отступ. Его кадры тоже сохранены.

`android21-report.json`: native2.0/contrast roundtrip, прокрутка подписи и доступность
всех9 карточек; исходные1.0/contrastOff и фраза подтверждены cold launch.
`ipad-and-native-accessibility-audit.json`: iPad2.0/restore1/1,122.404s; обычная
доска прошла6-type штатный audit на iPhone1/1,17.530s и iPad1/1,15.757s, без filters.
Dynamic Type и actual screen-reader navigation этим не проверены.

`dark-native-evidence.json`: session dark →6-type audit → light, iPhone1/1,58.446s
и iPad1/1,55.305s; исходные кадры просмотрены, persisted settings не менялись.
Пользователь выбрал стратегию прокрутки (`large-text-strategy-approval.json`),
но всю font/system-scale/accessibility матрицу и physical/AAC пока не принимал.

## System Dynamic Type: iPad17

`system-text-ipad-evidence.json`: действительная системная категория large →
accessibility-extra-extra-extra-large → large,3/3 native PASS. Исходные PNG
показывают изменение шрифта продукта; UIFont17/53/17pt в TXT — метрика runner,
не утверждение о точном размере шрифта карточек.

`system-text-ipad-scroll-evidence.json`: отдельный максимальный system size с
прокруткой подписи1/1,28.262s; окончание КАКАТЬ и оставшаяся картинка просмотрены,
фраза/audio state не изменились. Возвращён large, затем cold app1/1,20.705s.
Portable scale оставлен1.0, системная категория восстановлена даже при ошибке
теста. iPhone system maximum и произведение system maximum × portable2.0
ещё OPEN; эти кадры не закрывают всю матрицу.
