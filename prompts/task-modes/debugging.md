# Debugging

## First
- gather evidence before editing code
- inspect existing failure evidence first
- reproduce the issue only when runtime execution is authorized; otherwise say
  what could not be reproduced
- compare expected behavior with actual behavior

## Investigation
- inspect the nearest failing path first
- prefer small confirming experiments over broad rewrites
- add temporary logging or instrumentation only when needed

## Change
- fix the confirmed cause, not only the visible symptom
- remove temporary debug-only edits unless they are part of the final solution

## Validate
- add a regression test when practical
- if authorized, run the focused reproduction path once
- rerun only after a relevant change; ask before widening scope
- report the evidence chain from symptom to fix
