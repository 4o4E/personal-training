# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`GLOSSARY.md`** at the repo root, or
- **`GLOSSARY-MAP.md`** at the repo root if it exists: it points at one `GLOSSARY.md` per context. Read each one relevant to the topic.
- **Official OpenSpec project context**: follow the project's existing configuration and installed OpenSpec workflow to find relevant current specs and confirmed decisions. Read related change artifacts or archives when you need the rationale. Proposed changes and historical decisions are not automatically current constraints.

A missing glossary does not block exploration. If OpenSpec is unavailable, existing readable records can still inform investigation; explicitly report the prerequisite when a task needs to record or update a decision.

## Project layout

Use the root glossary for a single context, or follow `GLOSSARY-MAP.md` to each relevant context. Record the discovered project-specific OpenSpec pointers here during setup. OpenSpec owns its artifact formats, locations and lifecycle; this file only points consumers to that existing setup.

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `GLOSSARY.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal: either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Respect confirmed decisions

When a proposal conflicts with a current confirmed decision, cite the relevant OpenSpec artifact and explain the concrete reason for reconsidering it. Obtain a decision on the substantive change before implementing it.

For recording human decisions or resolving domain terminology, call the Skill tool with "domain-modeling". It maintains the glossary and hands decision recording to the project's official OpenSpec workflow.
