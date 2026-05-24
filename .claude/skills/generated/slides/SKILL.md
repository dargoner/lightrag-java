---
name: slides
description: "Skill for the Slides area of lightrag-java. 13 symbols across 2 files."
---

# Slides

13 symbols | 2 files | Cohesion: 70%

## When to Use

- Working with code in `docs/`
- Understanding how write_xml, replace_slide_texts, keep_selected_slides work
- Modifying slides-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `docs/slides/generate_knowledge_governance_ppt.py` | write_xml, replace_slide_texts, keep_selected_slides, keep_slide_relationships, update_app_properties (+5) |
| `docs/slides/test_generate_knowledge_governance_ppt.py` | load_module, test_generates_three_slide_ppt_with_expected_content, test_generates_visual_enhanced_variant_with_expected_slides |

## Entry Points

Start here when exploring this area:

- **`write_xml`** (Function) — `docs/slides/generate_knowledge_governance_ppt.py:119`
- **`replace_slide_texts`** (Function) — `docs/slides/generate_knowledge_governance_ppt.py:124`
- **`keep_selected_slides`** (Function) — `docs/slides/generate_knowledge_governance_ppt.py:134`
- **`keep_slide_relationships`** (Function) — `docs/slides/generate_knowledge_governance_ppt.py:146`
- **`update_app_properties`** (Function) — `docs/slides/generate_knowledge_governance_ppt.py:168`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `write_xml` | Function | `docs/slides/generate_knowledge_governance_ppt.py` | 119 |
| `replace_slide_texts` | Function | `docs/slides/generate_knowledge_governance_ppt.py` | 124 |
| `keep_selected_slides` | Function | `docs/slides/generate_knowledge_governance_ppt.py` | 134 |
| `keep_slide_relationships` | Function | `docs/slides/generate_knowledge_governance_ppt.py` | 146 |
| `update_app_properties` | Function | `docs/slides/generate_knowledge_governance_ppt.py` | 168 |
| `keep_slide_overrides` | Function | `docs/slides/generate_knowledge_governance_ppt.py` | 157 |
| `pack_pptx` | Function | `docs/slides/generate_knowledge_governance_ppt.py` | 186 |
| `generate_variant` | Function | `docs/slides/generate_knowledge_governance_ppt.py` | 194 |
| `generate_ppt` | Function | `docs/slides/generate_knowledge_governance_ppt.py` | 216 |
| `generate_visual_ppt` | Function | `docs/slides/generate_knowledge_governance_ppt.py` | 220 |
| `load_module` | Function | `docs/slides/test_generate_knowledge_governance_ppt.py` | 12 |
| `test_generates_three_slide_ppt_with_expected_content` | Method | `docs/slides/test_generate_knowledge_governance_ppt.py` | 21 |
| `test_generates_visual_enhanced_variant_with_expected_slides` | Method | `docs/slides/test_generate_knowledge_governance_ppt.py` | 92 |

## How to Explore

1. `gitnexus_context({name: "write_xml"})` — see callers and callees
2. `gitnexus_query({query: "slides"})` — find related execution flows
3. Read key files listed above for implementation details
