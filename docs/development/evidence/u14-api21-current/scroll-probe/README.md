# API21: preview найден, подтверждение ещё RED

Новый app не подменялся: установленный development APK по-прежнему
`9e3eb90139a592d9aece5c5f1855b74d7e6b34531d6d7f870a95cdcaec0c0a79`.
Менялся только instrumentation helper; guards/форматы/данные не ослаблялись.

После ожидания idle accessibility-событий за каждым scroll, вместо расходования
12 попыток до завершения асинхронной прокрутки, тот же макрос увидел **настоящий
preview1/9/9/9**. Однако после заявленного нажатия unlock подтверждение осталось
disabled, `readiness.fresh=false`. Оба следующих прогона RED на confirm, не PASS.

`confirm-timeout.png` — screenshot **в точке timeout до ActivityScenario cleanup**,
получен через UiAutomation и собственный cache-файл. Это не launcher после ошибки.
Видны реальные счётчики и выключенное подтверждение. Trace следующего прогона
сохранил auth named bounds `[36,375][1044,520]`; центр не находится под toolbar,
поэтому гипотеза простого пересечения toolbar не подтверждается.

Следующая проверка меняет только input delivery теста на native accessibility
`ACTION_CLICK` того же visible/enabled clickable ancestor. Она не подставляет
capability/не обходит `AssistantGate` и не заменяет physical touch acceptance.
Auth/lifecycle/UI state причины пока не установлены; библиотека/фраза не очищались.
