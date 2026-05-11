# Sessie-documentatie

Deze map bevat markdown-documenten van Claude/Gemini/Codex sessies op deze repo, conform het **Prompt Sessie Documentatie Protocol** van Meta_Master (zie `Meta_Master/CLAUDE.md` regel 331+).

## Wat hier hoort

- Eén markdown-bestand per sessie (of per logisch sessie-segment)
- Bestandsnaam: `YYYY-MM-DD_korte_onderwerp_slug.md`
- Template: zie `Meta_Master/templates/PROMPT_SESSION_TEMPLATE.md`

## Wat hier NIET hoort

- Memory-bestanden (die staan in `~/.claude/projects/.../memory/` en in `Meta_Master/claude_memory/`)
- Output-artefacten van de sessie (die staan elders in deze repo)
- Tijdelijke notities (gebruik `/tmp/` of een lokale notitie-app)

## Waarom

Conversatie-context en besluitvorming blijven onlosmakelijk met de repo verbonden. Toekomstige sessies (jezelf of andere agenten) kunnen direct begrijpen waarom keuzes gemaakt zijn zonder de oorspronkelijke conversatie nodig te hebben.

---

*Onderdeel van het Meta_Master ecosysteem · cpaglebbeek*
