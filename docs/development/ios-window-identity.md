# iOS: UIKit identity, а не Kotlin wrapper identity

## Подтверждённая причина

Actual filled-fixture UI test остановился на `03-import-name` до Files: у реальной
пустой библиотеки пункты импорта/настроек/защиты disabled. Startup/relaunch smoke
проверял visibility/hittability, а не readiness защищённых действий; его PASS
не доказывал исправность этого маршрута.

Цепочка: `LinkaApp.commandsEnabled` ← window.foreground ← `IosProductRoot.snapshot`
← `rootWindowSnapshot`. Диагностический Native log показал на iOS17.0:

```text
open=true current=true cached=true same=false nativeSame=true
app=UIApplicationStateActive hidden=false
active=false foreground=false accessReady=true
```

`WeakReference`/UIKit getter возвращают разные Kotlin wrappers одного ObjC UIWindow.
Сравнение `!==` ошибочно классифицировало собственное окно как чужое. `nativeSame`
проверен публичным `kotlinx.cinterop.objcPtr()`, без structural equality/hash или
глобального key-window поиска. Foundation alias/foreign/null identity test PASS.
Дополнительный snapshot regression сначала **RED:1 failed/2 passed**, expected
own active window вместо ложного отказа; исходный fail закрывал команды безопасно,
но делал UI неработоспособным.

## Граница исправления

Snapshot получает точный comparator; native root передаёт ObjC pointer identity.
Тем же helper сопоставляются реальные window-hidden и scene-deactivate/disconnect/
background notifications. Kotlin-owned Window/caller/capability tokens сохраняют
свои `===` проверки; авторизация/credential/DB writers не меняются.

- То же native окно, другой wrapper: прежние application/scene/hidden checks.
- Другое native окно/scene: по-прежнему отказ/изоляция, не любое active окно.
- Closed/disconnected/hidden/background: отказ, синхронная lifecycle-инвалидация.
- Inactive: не fresh admission; разрешённый старым контрактом own cached modal
  fallback не расширяется на чужое окно.
- Не добавлены autounlock, повтор операций, изменение Keychain group/schema или
  profile/security bypass. После смены сборки данные не очищаются.

После поправки **5/5 targeted Native tests PASS**: alias/foreign/null, snapshot
closed/hidden/background/modal и owner registry. Actual enhanced XCUI startup/
relaunch **1/1 PASS,11.920s**: проверяются также enabled controls, в том числе
настоящая button-role FAB. Import теперь действительно открывает transfer screen
с активными командами; следующий RED — selector имени поля, а не прежнее disabled
меню. Filled Files flow и финальный Native gate ещё не приняты.

Временные `[DEBUG-u14-*]` traces удалены из source до итоговой проверки. Это не
полный P08/G4/physical acceptance и не ослабление native window isolation.
