# Audio + составные leases, 6 ноября

`results.json` связывает actual test outcomes, source/artifact SHA и локальные
logs/xcresult. Только выделенные LINKa эмуляторы и Simulator, существующие
синтетические доски. Production/data reset/переимпорт/выдача permissions не нужны.

Исправлены две разные причины: отсутствующий CInterop в custom XCTest framework
и отказ storage читать через действительный составной application lease.
Ни asset membership, ни совпадение SHA сами по себе не заменяют proof ownership.

UI-сценарий: настоящее завершение записей → PLAYING → Stop/IDLE → повторное
завершение → library/Back/reopen с той же фразой. Доказательство — assertions
product accessibility state и native completion, не один PNG. TTS/physical audio
quality/Stop output latency/offline/mic/routes/U1.4/G4/release остаются OPEN.

`ios26-callback-evidence.json` отдельно сохраняет более поздний положительный
hosted clip/TTS результат на iOS26.5:2/2 PASS,2.480s. Minimum iOS17 TTS остаётся
environment RED; этот positive не доказывает offline/Russian/physical readiness.
