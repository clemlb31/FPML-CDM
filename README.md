# interface_gen — Agent LangGraph FpML → CDM

Agent autonome qui génère un convertisseur Java FpML 5.x → CDM 6.x. À chaque exécution :
1. Analyse la structure FpML et CDM via deux sous-LLM spécialisés.
2. Planifie une liste de méthodes Java (un *MethodSpec* par concern).
3. Écrit un squelette Maven (`pom.xml`, `IrsTransformer.java`, `FpmlToCdmApp.java`, `SemanticDiff.java`).
4. Demande au LLM de remplir chaque méthode séquentiellement.
5. Compile dans un conteneur Docker, exécute le JAR sur une paire FpML/CDM de test.
6. Si erreurs : triage déterministe → patch ciblé d'une méthode → boucle (8 fois max).

---

## Architecture du graphe d'états

```mermaid
flowchart TD
    START([__start__]):::terminal --> route

    route{{route<br/>switch state.mode}}:::router
    route -- mode==generate --> disambig_init
    route -- mode==patch --> patch

    subgraph disambig["disambig_init<br/><i>crée knowledge_base/rules/disambiguation.md si absent</i>"]
        direction TB
        D1[get_disambiguation_rules MCP]:::tool --> D2{fichier &gt;200 chars ?}:::router
        D2 -- oui --> Dskip[preserve existing]:::pure
        D2 -- non --> D3[fetch training pairs + xpath_guide + irs.md]:::tool
        D3 --> D4[LLM: extrait les ambiguïtés et enum mappings]:::llm
        D4 --> D5[write_disambiguation_rules MCP]:::tool
    end
    disambig_init --> plan

    subgraph planN["plan<br/><i>3 sous-appels LLM → list&lt;MethodSpec&gt;</i>"]
        direction TB
        P1[get_irs_xpath_guide MCP]:::tool --> P2[LLM plan/fpml<br/>spécialiste FpML XPath]:::llm
        P3[get_training_pairs + get_cdm_class_hierarchy MCP]:::tool --> P4[LLM plan/cdm<br/>spécialiste CDM Java types]:::llm
        P5[get_learned_schema + get_disambiguation_rules MCP]:::tool
        P2 --> P6
        P4 --> P6
        P5 --> P6["LLM plan/synth<br/>combine analyses → JSON list&lt;MethodSpec&gt;"]:::llm
    end
    plan --> skeleton

    subgraph skel["skeleton<br/><i>pure Python — pas de LLM</i>"]
        direction TB
        S1[get_maven_dependencies MCP]:::tool --> S2[build_pom + build_skeleton]:::pure
        S2 --> S3[write_file × 4<br/>pom.xml + IrsTransformer.java<br/>+ FpmlToCdmApp.java + SemanticDiff.java]:::tool
    end
    skeleton --> fill_methods

    subgraph fill["fill_methods<br/><i>1 appel LLM par MethodSpec, séquentiel</i>"]
        direction TB
        F1[read_file IrsTransformer.java]:::tool --> F2[get_irs_xpath_guide<br/>+ get_cdm_enum_mappings<br/>+ get_cdm_date_handling<br/>+ get_cdm_global_key_guide<br/>+ get_disambiguation_rules<br/>+ get_learned_schema]:::tool
        F2 --> F3{pour chaque MethodSpec}
        F3 --> F4[read_cdm_snippet]:::tool
        F4 --> F5[LLM fill/&lt;method&gt;<br/>écrit body Java]:::llm
        F5 --> F6{stub_throw<br/>présent dans source ?}:::router
        F6 -- oui --> F7[source.replace stub→body, 1]:::pure
        F6 -- non --> F3
        F7 --> F3
        F3 -- tous traités --> F8[write_file consolidé]:::tool
    end
    fill_methods --> compile

    subgraph comp["compile<br/><i>mvn clean compile dans conteneur Docker</i>"]
        direction TB
        C1[compile_project MCP<br/>= docker exec mvn]:::tool --> C2[parse_compile_errors]:::pure
        C2 --> C3[(state.compile_errors)]:::state
    end
    compile -- errors vides --> test
    compile -- errors AND iter &gt;= 8 --> done
    compile -- errors AND iter &lt; 8 --> patch

    subgraph testN["test<br/><i>package + run JAR sur 1 paire FpML/CDM</i>"]
        direction TB
        T1[run_test MCP<br/>= mvn package + java -jar + json_diff]:::tool --> T2[(state.transform_diffs)]:::state
    end
    test --> schema_learn

    subgraph slearn["schema_learn<br/><i>enregistre les observations dans knowledge_base/</i>"]
        direction TB
        SL1[LLM: résume itération<br/>compile/test status + méthodes]:::llm --> SL2[update_learned_schema MCP]:::tool
        SL2 --> SL3[append_trace_entry MCP<br/>jsonl iteration trace]:::tool
    end
    schema_learn -- diffs vides --> done
    schema_learn -- diffs AND iter &gt;= 8 --> done
    schema_learn -- diffs AND iter &lt; 8 --> patch

    subgraph patchN["patch<br/><i>réécrit UNE méthode via triage déterministe + LLM ciblé</i>"]
        direction TB
        PA1[read_file IrsTransformer.java]:::tool --> PA2{compile_errors<br/>OR transform_diffs ?}:::router
        PA2 -- compile_errors --> PA3[triage_compile_error MCP<br/>line → target_method + fix_hint]:::tool
        PA2 -- transform_diffs --> PA4[triage_test_diff MCP<br/>cdm_path → target_method]:::tool
        PA3 --> PA5
        PA4 --> PA5[extract_method_source MCP]:::tool
        PA5 --> PA6[fetch reference_tools<br/>get_disambiguation_rules + ref docs]:::tool
        PA6 --> PA7[LLM patch/&lt;method&gt;<br/>réécrit le corps de la méthode]:::llm
        PA7 --> PA8[replace_method_body + write_file]:::tool
        PA8 --> PA9[iteration++]:::state
    end
    patch --> compile

    done([done<br/>print final status]):::terminal --> END([__end__]):::terminal

    classDef terminal fill:#bfb6fc,stroke:#5b21b6,color:#000,font-weight:bold
    classDef router fill:#fef3c7,stroke:#d97706,color:#000
    classDef llm fill:#fbcfe8,stroke:#be185d,color:#000
    classDef tool fill:#bfdbfe,stroke:#1e40af,color:#000
    classDef pure fill:#bbf7d0,stroke:#15803d,color:#000
    classDef state fill:#fde68a,stroke:#a16207,color:#000
```

**Légende des couleurs**

| Couleur | Signification |
|---------|---------------|
| 🟣 Violet | Nœud terminal (`__start__`, `done`, `__end__`) |
| 🟡 Jaune | Routage conditionnel |
| 🩷 Rose  | Appel LLM (via `helpers.llm_text_or_raise`) |
| 🔵 Bleu  | Outil MCP (filesystem, triage, validator, mapping) |
| 🟢 Vert  | Code Python pur (pas d'I/O LLM ni MCP) |
| 🟠 Orange| Mutation du `AgentState` |

---

## Comptage des appels LLM (FRA, 11 MethodSpecs)

| Phase | Appels LLM | Notes |
|------|------------|-------|
| `disambig_init` | 1 ou 0 | Skip si `disambiguation.md` existe déjà |
| `plan` | 3 (fpml, cdm, synth) | Sub-appels parallèles puis synthèse |
| `skeleton` | 0 | Pur Python |
| `fill_methods` | N = nb de specs | Séquentiel — `2500 max_tokens` chacun |
| `compile` | 0 | Docker `mvn clean compile` |
| `test` | 0 | Docker `mvn package` + `java -jar` + diff JSON |
| `schema_learn` | 1 | Résumé observations |
| `patch` | 1 par itération | Triage MCP déterministe → réécrit 1 méthode |

**Total typique pour un FRA** : ~16 appels LLM en chemin nominal (sans patch), jusqu'à ~24 si la boucle patch tourne 8 fois.

---

## Backends LLM supportés

Configuré via `LLM_BACKEND` dans `.env`. Chaque backend respecte la même interface `helpers.llm_text_or_raise` (route via OpenAI Async client) :

| Backend | URL | Variable modèle | Notes |
|---------|-----|------------------|-------|
| `gemini` | `https://generativelanguage.googleapis.com/v1beta/openai/` | `GEMINI_MODEL` | Free tier limité : 20 RPD sur 2.5-flash dans certains projets |
| `groq`   | `https://api.groq.com/openai/v1` | `GROQ_MODEL` | Free : ~100k TPD sur llama-3.3-70b, très rapide |
| `ollama` | `http://localhost:11434/v1` | `OLLAMA_MODEL` | Local, illimité ; qwen3 thinking mode désactivé via `/no_think` |
| `vllm`   | `$VLLM_BASE_URL` | `VLLM_MODEL` | Réseau Murex (qwen 27B) |
| `copilot`| GitHub Models | `--model` arg | GH PAT avec scope `models:read` |

Backend par défaut : `ollama` + `qwen3.5:4b` (lent mais sans quota).

Retry automatique avec backoff exponentiel sur 429/503/timeouts (5 tentatives).

---

## Stack MCP (5 serveurs)

| Serveur | Port | Tools exposés |
|---------|------|---------------|
| **filesystem** (supergateway → `@modelcontextprotocol/server-filesystem`) | 8080 | `read_file`, `write_file`, `create_directory`, `list_directory`, … (lecture/écriture sur `workspaces/`, `knowledge_base/`, `data/train`, `data/test`) |
| **triage** (Python FastMCP) | 8002 | `triage_compile_error`, `triage_test_diff` |
| **validator** (Python FastMCP, container Docker) | 8003 | `compile_project`, `run_test`, `run_test_all`, `run_arbitrary_test`, `extract_method_source`, `list_test_suites`, `get_test_cases`, `score_with_llm` |
| **mapping** (Python FastMCP) | 8004 | `get_maven_dependencies`, `ask_human` |
| **tavily** *(optionnel)* | `${TAVILY_MCP}` | Recherche internet pour spec lookups CDM/FpML |

---

## Lancer l'agent (Mac/Linux)

### Pré-requis
- Python 3.13, Node.js + npx, Docker Desktop (pour `validator`)
- `.env` à compléter : choix backend + clé API correspondante

### Setup
```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt

# Démarrer Docker Desktop (le validator a besoin du daemon)
open -a Docker

# Démarrer les 5 serveurs MCP (foreground, Ctrl+C arrête tout)
bash scripts/start_servers.sh
# OU en background :
bash scripts/start_servers.sh > /tmp/mcp.log 2>&1 &
```

### Lancer une exécution
```bash
.venv/bin/python -m agent.graph \
  --fpml      data/test/rates-5-10/fpml/ird-ex08-fra.xml \
  --expected  data/test/rates-5-10/cdm/ird-ex08-fra.json \
  --out       workspaces/test-fra
```

### Arrêter
```bash
bash scripts/start_servers.sh --stop
```

---

## Structure du repo

```
agent/
  graph.py              # LangGraph state machine — 10 nodes
  react_graph.py        # Alternative ReAct loop (non utilisé par graph.py)
  helpers.py            # _llm_text_or_raise, build_pom, build_skeleton, unwrap, …
  llm_call/             # Factory + backends (gemini, groq, ollama, lmstudio, vllm, copilot)
mcp_servers/
  triage_server/        # Pattern matching erreurs compile/test → target method
  validator_server/     # Docker container + mvn compile/package + JSON diff
  mapping_server/       # Maven deps + ask_human
  dev_server/           # Placeholder (supergateway sur workspaces/)
  knowledge_server/     # Placeholder (supergateway sur knowledge_base/)
knowledge_base/
  reference/cdm/        # CDM type hierarchy, enum mappings, date handling
  reference/fpml/       # FpML XPath guides
  rules/                # irs.md, disambiguation.md (corrections humaines)
  knowledge/            # learned_schema, iteration_trace.jsonl, cdm_class_decisions
data/
  train/                # 360+ paires FpML/CDM par famille produit (entraînement)
  test/                 # Paires utilisées par le validator
workspaces/
  test-fra/             # Projet Maven généré par l'agent (overwrite à chaque run)
scripts/
  start_servers.sh      # Démarre les 5 MCP servers (Mac/Linux)
  start_servers.ps1     # Equivalent Windows
  test_llm.py           # Smoke test du backend LLM configuré
configs/
  agent.yaml            # Config vLLM
  mcp.yaml              # URLs des serveurs MCP utilisés par le graph
```
