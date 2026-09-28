# Terminal policy decision

## Decision

The release default is `literal` (strict). The `relaxed` result is retained as an alternative interpretation and is never presented as official compliance.

## Source text

The current technical appendix, §2.2, states:

> Для полигона, содержащего целевую точку подключения ОКС (`oks_connection_point`), допускается один финальный прямой участок от ближайшей к точке границы полигона до самой точки. Требование отступа к собственному полигону на этот участок не распространяется, в том числе на его часть в зоне отступа перед границей. Остальные ограничения продолжают действовать.

The same rule appears in the written clarification for question 3. The source text is preserved in `task/text/Техническое  приложение ЛЦТ.txt:45-46` and `task/text/Разъяснения по вопросам ЛЦТ.txt:8`.

The general task text also says that the competition set is prepared so that all prospective OKS can be connected (`task/text/ДИТ.txt:242`). Those statements create a practical tension on targets 2, 5, and 10: a global-nearest-boundary terminal ray is blocked, while another legal-looking exit is available.

## Strict behavior

`literal` uses the global nearest boundary point for each containing OKS polygon. On the corrected official dataset, targets 2, 5, and 10 remain unconnected and receive the official penalty. The independent audit reports zero `ERROR` findings under `--terminal-policy strict`.

- v1: 14/17, C = 568,862,239.7813864 RUB, L = 1,553.6517006113452 m, S = 20.589097815712854
- v2: 14/17, C = 595,418,295.9594457 RUB, L = 1,805.6816029830904 m, S = 22.08875709581375

## Alternative behavior

`relaxed` keeps the same obstacle and cost rules but permits an available exterior terminal exit when the literal nearest exit is blocked by the containing OKS geometry. The output records `diag_terminal_policy=relaxed` and `diag_relaxed_final_approach` so the deviation is visible.

- v1: 17/17, C = 271,516,096.5294552 RUB, L = 1,824.6731652331825 m, S = 13.07647019852429
- v2: 17/17, C = 289,678,319.56243277 RUB, L = 2,078.298335062663 m, S = 14.345887952936106

The independent audit reports zero structural `ERROR` findings under `--terminal-policy any`, with six `AMBIGUOUS` findings identifying the three relaxed terminal approaches in both variants. The independent Python validator also returns zero errors and marks the relaxed approaches as warnings.

## Why strict is the default

No newer organizer clarification is included in this handoff. The literal sentence is an explicit technical-app rule, so release and presentation default to the conservative strict interpretation. The alternative remains available for review and for a future switch if the organizers publish a clarification that resolves the conflict.

## Switching after an official clarification

Set `HEATNET_TERMINAL_POLICY=relaxed` for the alternative implementation, or `literal` for submission-safe strict mode. In the viewer, choose `Строгая трактовка` or `Альтернативная трактовка`; each mode loads its own output and metrics.

## Evidence

- Corrected dataset SHA-256: `cffb7133419d93fe364a53015a7d3ead289f671cbfaf6f4be87f2a21914130`.
- Strict output SHA-256: `f2dbbf86f1d669a8dd30b44d3de63c573bfe9c1bfbb43b02e118998535b17070`.
- Alternative output SHA-256: `150743451942d7ed895158905de2da815c04c2eb95339f49da5ef3a8cd1b0d80`.
- Audit logs are kept in `evidence/logs/` in the evidence archive.
