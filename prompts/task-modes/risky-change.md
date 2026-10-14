# Risky Change

## Before Editing
- explain the blast radius
- identify the contracts, data, or environments at risk
- say what rollback or recovery path exists, if any

## Change Strategy
- prefer phased work over one large edit
- avoid broad refactors unless they are required for correctness
- separate verified facts from expected outcomes

## Validate
- design a stronger phased validation plan, but do not execute it automatically
- use static boundary and rollback review first
- ask once before the first runtime phase, with exact commands and cost
- ask again before widening beyond the approved scope
- call out what still needs human review or rollout checks
