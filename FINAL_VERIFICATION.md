# Final verification matrix

| Check | Result | Evidence |
|---|---|---|
| Java unit/integration tests | NOT VERIFIED | `service/mvnw.cmd -B test` reached compilation and stopped because this host exposes a JRE: `No compiler is provided in this environment. Perhaps you are running on a JRE rather than a JDK?` |
| Viewer policy tests | PASS (3/3) | `cd viewer && npm run test:policy` |
| Frontend typecheck | PASS | `cd viewer && npm run typecheck` |
| Frontend production build | PASS | `cd viewer && npm run build` (Vite warning: one 1.3 MB chunk) |
| docker-compose config | NOT VERIFIED | Docker/docker-compose is unavailable in this host; Ubuntu command is in `FINAL_BLOCKERS.md` |
| Docker build | NOT VERIFIED | Docker unavailable |
| Service startup / API official dataset E2E | NOT VERIFIED | Requires Ubuntu 22, JDK 11 and Docker |
| Strict independent audit | PASS | `lct_audit.py --terminal-policy strict`: exit 0, ERROR 0, AMBIGUOUS 0, WARNING 6 |
| Alternative independent audit | PASS_WITH_DISCLOSURE | `lct_audit.py --terminal-policy any`: exit 0, ERROR 0, AMBIGUOUS 6, WARNING 7 |
| Strict independent Python validator | PASS | exit 0, variant v1/v2 errors 0 |
| Alternative independent Python validator | PASS_WITH_DISCLOSURE | exit 0, variant v1/v2 errors 0; warnings identify relaxed exits |
| Strict output contract | PASS | `output_contract_check.py`: exit 0, no errors |
| Alternative output contract | PASS_WITH_WARNING | exit 0, one micro-segment warning |
| Deterministic hashes | PASS | hashes are recorded in `FINAL_METRICS.json` and `docs/TERMINAL_POLICY_DECISION.md` |
| Browser runtime | PASS for fresh dev load | In-app browser loaded strict default with no console errors; selector switched to alternative and showed 17/17, S 13.076, and disclosure. |
| Stale metric scan | PASS for release-facing docs and presentation | Old relaxed KPI text remains only where it is explicitly labelled as alternative or in factual GeoJSON/audit evidence |
| Hardcode scan | PASS | No dataset coordinates or routing exceptions found in Java solver; numeric constants are rule tables and geometry tolerances |
| Secrets scan | PASS | No credential files, private keys, tokens, or `.env` files in the staged release |

The default release policy is strict/literal. The alternative output remains available as a separately disclosed interpretation. The only release blocker is the unavailable JDK 11/Docker execution gate described in `FINAL_BLOCKERS.md`.
