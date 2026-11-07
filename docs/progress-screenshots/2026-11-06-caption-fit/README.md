# Caption word-fit: native verification

Same existing synthetic boards, no reimport/reset or message edits. Android21
reopen/native Back1/1 PASS (1.714s), iPhone/iOS17 compose/reopen1/1 PASS (31.139s).
Source fix uses measured words and base typography; inherited system/portable
font scaling remains enabled. Actual PNG are reviewed separately from unit tests.
Full large-font/accessibility/U1.4 acceptance is not implied by this default-scale
check. API21 GIF alpha remains a distinct decoder issue, not part of this fix.
