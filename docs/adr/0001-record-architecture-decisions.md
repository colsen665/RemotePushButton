# 1. Record architecture decisions

- Status: Accepted
- Date: 2026-10-15

## Context
The design went through several iterations: ESP32 + home server, then Tailscale to an old phone, then an end-to-end encrypted relay. We need the reasons behind each decision to survive into later sessions, for both humans and coding agents.

## Decision
- **One ADR per decision**, in `docs/adr/NNNN-kebab-title.md`, each with the sections Status, Context, Decision, Consequences, and Alternatives.
- **Superseding:** a superseded ADR is kept and marked `Superseded by NNNN`.
- **`AGENTS.md`** at the repo root is the entry point for agents: the overview and the conventions.
- **`TODO.md`** holds the decision log (one row per ADR) and the task list. Every task references the ADR(s) it implements.
- **`CLAUDE.md`** only imports `AGENTS.md`, so tool-specific files never drift from it.

## Consequences
- Any change to the architecture, protocol or security model comes with a new or updated ADR, and an update to the decision table in `TODO.md`.
