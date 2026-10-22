---
name: domain-modeling
description: Build and sharpen a project's domain model. Use when discussing codebase terminology, writing or editing a GLOSSARY.md, or recording or revisiting project decisions with OpenSpec.
---

# Domain Modeling

Actively build and sharpen the project's domain model as you design. This is the *active* discipline: challenging terms, inventing edge-case scenarios, and writing the glossary and decisions down the moment they crystallise. (Merely *reading* `GLOSSARY.md` for vocabulary is not this skill: that's a one-line habit any skill can do. This skill is for when you're changing the model, not just consuming it.)

## Project documentation

Use the root `GLOSSARY.md` for a single context. When `GLOSSARY-MAP.md` exists, follow it to the relevant context's glossary. Create a glossary only when the first term is resolved.

Use the target project's installed [official OpenSpec](https://github.com/Fission-AI/OpenSpec) skills or commands for decision records. Read the project's configuration and relevant existing specs and change artifacts before recording or revisiting a decision. Distinguish current accepted decisions from proposals and historical choices; consult related archives when their rationale matters.

OpenSpec owns artifact formats, locations, change selection and lifecycle operations. Follow its installed workflow and the project's existing configuration; this skill provides the confirmed decision as input. Keep this integration as a handoff to OpenSpec, without adding a parallel decision store or maintaining OpenSpec templates, schemas or commands here.

If official OpenSpec is unavailable or the target project needs initialization, report that prerequisite and that decision recording remains incomplete. Continue independent glossary work or investigation where useful; use the official setup workflow when setup is authorized.

## During the session

### Challenge against the glossary

When the user uses a term that conflicts with the existing language in `GLOSSARY.md`, call it out immediately. "Your glossary defines 'cancellation' as X, but you seem to mean Y. Which is it?"

### Sharpen fuzzy language

When the user uses vague or overloaded terms, propose a precise canonical term. "You're saying 'account': do you mean the Customer or the User? Those are different things."

### Discuss concrete scenarios

When domain relationships are being discussed, stress-test them with specific scenarios. Invent scenarios that probe edge cases and force the user to be precise about the boundaries between concepts.

### Cross-reference with code

When the user states how something works, check whether the code agrees. If you find a contradiction, surface it: "Your code cancels entire Orders, but you just said partial cancellation is possible. Which is right?"

### Update GLOSSARY.md inline

When a term is resolved, update `GLOSSARY.md` right there. Don't batch these up: capture them as they happen. Use the format in [GLOSSARY-FORMAT.md](./GLOSSARY-FORMAT.md).

`GLOSSARY.md` should be totally devoid of implementation details. Do not treat `GLOSSARY.md` as a spec, a scratch pad, or a repository for implementation decisions. It is a glossary and nothing else.

### Record confirmed decisions through OpenSpec

Whenever human input settles or changes a decision, record it through the project's official OpenSpec workflow at that point in the session. This includes business rules, scope, acceptance criteria, architecture and exceptions. Capture the confirmed choice, its scope, the actual confirmation source, and the reasons or rejected alternatives the user supplied. Keep unstated reasoning unknown and distinguish the agent's recommendations from the user's decisions.

Use the relevant existing change, or let the official workflow establish one when needed. A repeated confirmation can reference the existing record. Recording an already confirmed decision needs no additional question; ask only when a substantive unresolved choice affects the outcome. When a decision changes, use OpenSpec to update the affected artifacts and make the new scope clear.

Before reporting a decision as recorded, verify that the official workflow persisted it and report its location. Updating the glossary alone does not complete decision recording.
