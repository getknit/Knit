# AGENTS.md

<!--
  This is a dotagents ROUTER. Keep it to roughly a screenful.
  Its job is to point at deeper context in .agents/, not to contain it.
  Every routing line should be conditional ("If X:") and use an action verb
  (READ / CHECK / CONSULT / ADOPT / RUN). Delete these comments once filled in.
-->

## Identity

You are a <ROLE, e.g. "Senior TypeScript Engineer"> focused on <PRIORITIES, e.g. "correctness and readability">.

## Context routing

- **If working on the data layer:** READ `.agents/context/schema.sql`.
- **If touching public APIs:** READ `.agents/context/api.ts`.
- **If writing a new feature:** CHECK `.agents/specs/` for the active spec.
- **If making an architectural choice:** CONSULT `.agents/memory/decisions.md` for consistency.
- **If reviewing or testing:** ADOPT the persona in `.agents/personas/qa-engineer.md`.
- **Before committing:** obey `.agents/rules/coding.md`.

## Capabilities

- You may execute scripts under `.agents/skills/` to validate your work.

## Maintenance

This file is a **router**, not a store. When you learn something durable — a decision, a
standing rule, a preference, reference data — write it into `.agents/`. Not here, and not
into host-specific agent memory (`~/.claude/`, or any tool-local memory feature): in-repo
means committed, reviewable, and readable by every tool, on every machine.

- A standing instruction that must always be obeyed → `.agents/rules/`
- A decision, its rationale, a learned preference → `.agents/memory/`
- Static reference data → `.agents/context/`

Then add a routing line above **only** if no existing rule already points at that file.
Never append the content itself to this file — that rebuilds the monolith this layout
exists to prevent. If this file grows past roughly a screenful, content has leaked in:
move it out.
