# modif.md — Journal des modifications (goal.md)

Objectif : pipeline FpML→CDM autonome **propre, robuste, lisible**, backend
**qwopus3.5-9B-Coder**, surface **4 MCP**. Chaque entrée = quoi + pourquoi.

Date : 2026-06-09. Branche : `interface_gen`.

---

## 1. Backend qwopus (nouveau modèle local)

- **`agent/llm_call/qwopus_call.py`** *(nouveau)* — backend `QwopusCall` (factory
  `LLM_interface`). Modèle de raisonnement servi par Ollama sur la machine GPU du LAN
  (`192.168.1.42:11434`). Via l'endpoint `/v1`, le `content` revient **propre** et le
  reasoning va dans un champ `reasoning` séparé → ne pollue pas les tool_calls.
  `max_tokens=8000` pour laisser la place reasoning + réponse. Fallback sur `reasoning`
  si `content` vide (troncature).
  **Pourquoi pas `think:false`** : testé, ça casse le template de ce finetune (le
  reasoning fuit dans `content` avec un `</think>` orphelin). On garde donc thinking ON.
- **`agent/llm_call/LLM_interface.py`** — `qwopus` enregistré dans la factory + ajouté
  à la liste des backends connus (message d'erreur).
- **`agent/helpers.py`** — entrée `qwopus` dans `_OPENAI_BACKEND_CONFIG` (c'est le chemin
  réellement utilisé par `autonomous.py`). `base_url_env=QWOPUS_BASE_URL`,
  `model_env=QWOPUS_MODEL`, `api_key="ollama"`, **pas** d'`extra_body` think.
- **Vérifié** : tool_use natif fonctionne (le modèle renvoie un `ToolCall` structuré),
  content propre, reasoning isolé.

## 2. Surface MCP réduite à 4 serveurs

Décidé : `filesystem`, `validator`, `grep`, `tavily`. Retrait de `mapping` + `triage`
+ `examples`.

- **`configs/mcp.yaml`** — réécrit : 4 serveurs. Retrait `triage` (8002) et `mapping`
  (8004). En-tête documente le « pourquoi un seul filesystem » (shadowing des tools par
  nom sous `MultiServerMCPClient`).
- **`agent/tools_registry.py`** — retrait de `get_maven_dependencies`,
  `triage_compile_error`, `triage_test_diff` de `EXPOSED_TOOLS`.
  **Pourquoi `get_maven_dependencies` peut partir** : les coords CDM sont déjà dans le
  system prompt (`cdm-java:6.19.0`, jackson 2.17.2).
- **`scripts/start_servers.sh`** — ne lance plus examples-8081/triage-8002/mapping-8004.
  3 process locaux : filesystem-8080 (monte `workspaces` + `knowledge_base` + `data`),
  validator-8003, grep-8005. (Tavily = MCP distant, pas de process.)
- **`scripts/start_servers.ps1`** — idem côté Windows.

**Sous-agents** : `spawn_subagent` reste un **méta-tool local** (pas un MCP). Un serveur
MCP devrait dupliquer le client LLM + la boucle ; le méta-tool ré-entre dans `run_loop`
en partageant LLM/tools/`project_dir`. La parallélisation existe déjà (asyncio.gather sur
les tool_calls d'un tour + `spawn_subagent`).

## 3. Robustesse (run non-surveillé)

- **`agent/autonomous.py`** :
  - `load_dotenv(ROOT/.env)` au démarrage → `LLM_BACKEND`/`QWOPUS_*`/`TAVILY_MCP` lus de
    façon fiable.
  - **Timeout par tool** (`_with_tool_timeout`) : 900 s pour `compile_project`/`run_test`
    (mvn Docker peut pendre / pull du jar), 120 s sinon. Un timeout devient un
    `<error>` et **ne tue pas la boucle**.
  - `asyncio.gather(..., return_exceptions=True)` + conversion des exceptions en
    `<error>` : un tool qui explose n'abat pas le tour.
  - **Setup MCP tolérant** : chargement **par serveur** (`get_tools(server_name=...)`).
    Un serveur optionnel qui échoue (ex. tavily 401) est **SKIPPÉ**, pas fatal.
  - **Arrêt propre** : `finally` ferme le client MCP ; `finish()` écrit toujours le
    résumé (SUCCESS / MAX_ITER / FAIL / ERROR).
  - `--max-iter` défaut 30 → **60** (run profondeur lancé à 120).
  - `SUBAGENT_SYSTEM_PROMPT` ne cite plus `get_maven_dependencies`.
  - Règle filesystem ajoutée au system prompt : **écrire uniquement sous `project_dir`**,
    `knowledge_base/` + `data/` en lecture seule.
- **`scripts/start_servers.sh`** :
  - `start()` ne wrappe plus dans un sous-shell → le **PID du vrai serveur** est capturé
    (sinon `--stop` ne tuait pas le bon process).
  - **`ready_check`** : attend que chaque endpoint MCP réponde réellement (HTTP) avant de
    déclarer prêt. Corrige le faux `[DOWN]` (la vérif PID 2 s après le lancement
    déclarait DOWN des serveurs encore en cold-start npx, alors qu'ils démarraient).

## 4. Observabilité (lisible au matin)

- **`agent/run_logger.py`** *(nouveau, indépendant)* — `RunLogger` :
  - stream chaque ligne vers stdout **et** `<project_dir>/run.log` ;
  - parse les résultats `compile_project` (ok/erreurs) et `run_test`
    (**score + nb de diffs**) pour ne logger que le signal utile ;
  - suit `best_score`, nb d'itérations, durée ;
  - écrit `<project_dir>/run_summary.json` final (statut, best_score, iters, durée…).
- **`agent/autonomous.py`** — logger de run au niveau module (`_RUN_LOGGER`), posé par
  `main()`, partagé par la boucle et les sous-agents (même process / `project_dir`). Les
  `print` de boucle passent par le logger.

## 5. Configuration & secrets

- **`.env`** — `LLM_BACKEND=manual` → **`qwopus`** ; bloc `QWOPUS_*` ;
  `TAVILY_MCP=https://mcp.tavily.com/mcp/?tavilyApiKey=${TAVILY_KEY}`.
  **Correctif Tavily** : le bon paramètre est `tavilyApiKey` (testé : `apiKey` → 401,
  `tavilyApiKey` → 200).
- **`.env.example`** — qwopus documenté, liste backends à jour, note Tavily
  (`tavilyApiKey`).

## 6. Documentation

- **`README.md`** — intro (backend défaut = qwopus), tableau backends (+ ligne qwopus),
  section « Stack MCP » 5→**4 serveurs**, diagramme mermaid mis en cohérence (retrait
  Triage/Mapping, ajout Grep, validator = compile+test), section structure du repo
  (retrait mapping/triage_server, ajout grep_server + run_logger.py + run.log/summary),
  pré-requis/commandes (3 serveurs, max-iter 60).
- **`goal.md`** *(nouveau)* — brief self-contained du chantier (pour `/goal`).

---

## Vérifications effectuées

- Imports `agent.autonomous` + `agent.run_logger` : OK.
- `get_servers()` → 4 serveurs, URL Tavily résolue avec la clé.
- `EXPOSED_TOOLS` : plus de mapping/triage/get_maven_dependencies.
- Backend qwopus : génération propre (content) + tool_use natif (ToolCall structuré).
- Setup agent : filesystem 14 + validator 8 + grep 1 + tavily 5 = 28 tools, aucun tool
  essentiel manquant.
- Run profondeur lancé : `workspaces/run-stub-amort-qwopus`
  (`ird-ex02-stub-amort-swap-versioned`, max-iter 120) — voir son `run.log` /
  `run_summary.json`.

---

## 7. Phase « perfs » (2e temps) — améliorations + findings

Le run baseline a révélé que qwopus-9B **divague** (boucle d'exploration) puis **stagne**.
Diagnostic itératif (runs v1→v4, FRA v1→v2) et correctifs apportés :

| # | Correctif (autonomous.py) | Pourquoi |
|---|---|---|
| a | **Steering du system prompt** : lire 1 fichier à la fois (pas `read_multiple_files` sur 2 gros fichiers), interdiction de list/search, « next call = write_file » | `read_multiple_files` sur FpML+JSON (~38k chars) faisait exploser le contexte → stall |
| b | **Dédup de lecture** (`_READ_PATHS`) : une re-lecture renvoie un stub court | le 9B re-lisait les mêmes fichiers chaque tour → contexte gonflé jusqu'à l'étouffement |
| c | **Garde anti-stagnation** (`_STALL_LIMIT`) : N tours « no-progress » (explore-only OU vide) → nudge fort `_WRITE_NUDGE` | sortir des boucles d'exploration / tours vides |
| d | **Fix bug harnais majeur** : ne plus injecter le placeholder `"[calling N tool(s): ...]"` comme tour assistant en mode natif → utiliser le **vrai texte** du modèle | le 9B **recopiait** ce placeholder en texte au lieu de réémettre les tool calls → boucle infinie de tours vides (cause racine des stalls v2/v3/v4) |
| e | **Logging diagnostic** : sur un tour sans tool call, on logge un extrait de `resp.text` | c'est ce qui a permis de voir le placeholder recopié (point d) |

**Effet mesuré** : avant, l'agent bouclait à l'infini (0 fichier écrit). Après, il
**lit → (garde) → écrit un vrai `pom.xml` sur disque**. Le bug du placeholder (d) était le
principal blocage et est éliminé.

**Finding honnête (limite modèle, pas pipeline)** : même corrigé, **qwopus-9B n'a pas la
fiabilité agentique** pour compléter ces tâches en autonomie — il écrit le pom.xml puis
émet le texte « I'll write the file… » sans attacher le `write_file` (le tool call ne sort
pas après le préambule). Il n'atteint pas compile/test sur le FRA. Le **pipeline est
robuste** (runs >5 min sans crash, logs limpides, garde + dédup actives, borné par
max-iter, résumé écrit).

**Pistes (non faites, pour la suite)** :
- `tool_choice="required"` (forcer un tool call) sur les tours de stall — risque vs le
  `<done>` en texte, à câbler proprement dans `helpers.py`.
- Modèle plus capable pour l'agentique : `vllm` qwen-27B, ou un Qwopus plus gros.
- Few-shot d'un run réussi (squelette Maven) injecté au system prompt.

---

## 8. Phase « lean context » — tenir dans 16-32k (RTX 4070)

**Cause racine réévaluée** : le modèle ne « divaguait » pas par bêtise — il **étouffait**.
Mesures : lire FpML (~4,6k tk) + JSON attendu (~7,6k tk) + system prompt (~0,7k) +
schémas des 13 tools (~1,2k) = **~14k tk juste pour lire**. À 16k de fenêtre Ollama, ça
déborde dès la 1re lecture → troncature silencieuse → le modèle « oublie » et re-lit.
Avec 32k (config actuelle de l'utilisateur, RTX 4070 12 GB), c'est tenable pour le FRA
mais le harnais gaspillait le contexte.

**Analyse VRAM (4070, 12 GB)** : poids 9B Q5 ≈ 7,4 GB → ~4 GB pour le KV-cache. 1 slot à
32k remplit ces ~4 GB. **2-3 agents parallèles ne tiennent QUE si chaque agent a un petit
contexte (~8-12k)**, pas 32k. La concurrence donne de la vitesse, pas plus de contexte par
appel → il faut `OLLAMA_NUM_PARALLEL=2/3` et des contextes par-agent réduits (option
`OLLAMA_KV_CACHE_TYPE=q8_0` pour caser plus de KV).

**Correctifs (autonomous.py)** :
- **Tool sets maigres par agent** (`_ORCHESTRATOR_TOOLS` 9, `_SUBAGENT_TOOLS` 7) câblés via
  `tool_names` dans `_run_loop`/`_run_subagent`. Schémas : 1179 → 876 (orch) / 640 (sub) tk.
  **Retrait de `list_directory`/`search_files`/`read_multiple_files`** de la vue du modèle
  → plus de tools de wandering, et plus de `read_multiple_files` qui dumpait 2 gros fichiers
  d'un coup.
- **Cap des gros tool_results** (`_cap_result`, `_MAX_RESULT_CHARS=12000`) : un `read_file`
  énorme est tronqué dans l'historique (~3k tk max) avec une note « utilise grep ». Le
  `record_tool` du logger voit toujours le résultat complet.
- **Prompts orientés lean** : orchestrateur → grep pour les gros fichiers + **déléguer aux
  sous-agents** (leur contexte frais ne consomme pas le sien, ils renvoient juste un
  `<done>`). Sous-agent → prompt maigre, grep-first, lecture unique.

**⚠️ Validation live à faire au retour du GPU** : ces changements sont raisonnés + import-
vérifiés mais **pas testés contre qwopus** (modèle indisponible au moment du dev). À
relancer : FRA puis stub-amort, vérifier que (a) l'orchestrateur délègue, (b) le contexte
reste sous la fenêtre, (c) on atteint compile/run_test. Garder le `run.log`/`run_summary`
pour comparer aux baselines v1-v4.

**Note archi** : on reste **LLM-driven** (délégation décidée par le modèle via
`spawn_subagent`), fidèle à « aucun nœud fixe ». Si le 9B ne délègue pas de lui-même, le
prochain pas serait un orchestrateur à phases déterministes (scaffold → transformer) — mais
ça s'écarte de la philosophie du projet, à discuter.

---

## 9. Validation live (GPU sur 172.29.155.90, 32k) + correctifs web-sourcés

`.env` `QWOPUS_BASE_URL` → `172.29.155.90:11434`. num_ctx servi = **32768**.

Runs de validation `workspaces/val-fra-lean*` sur le FRA. Le **trace.jsonl** (nouveau, voir
ci-dessous) a permis de diagnostiquer chaque blocage précisément.

**Logging enrichi (`run_logger.py`)** : nouveau **`trace.jsonl`** (1 ligne/tour : texte du
modèle + **reasoning** + tool calls avec args + aperçu des résultats) en plus du `run.log`.
`RunLogger.turn()` remplace `note_tools`. C'est ce qui a rendu les bugs visibles.

**Bugs trouvés via le trace + correctifs :**
- **Chemin d'entrée mal résolu** (gros) : le modèle préfixait le `project_dir` aux chemins
  relatifs → lisait des fichiers inexistants → ne récupérait jamais le FpML. Fix :
  `main()` passe des **chemins absolus** (résolus) dans le user prompt. → il lit enfin.
- **`tool_choice="required"`** (tenté pour forcer un tool call) : plumbé dans
  `helpers.llm_call`/`_openai_compat_call`. **Sur ce GGUF, Ollama devient ~30× plus lent**
  (sampling sous grammaire) ET renvoie quand même 0 tool call → **abandonné** dans la boucle
  (plumbing gardé pour backends plus costauds).
- **Boucle de réécriture** (recherche web : *debounce + fingerprint + clear state*, cf.
  sources) : le modèle réécrivait `pom.xml` en boucle (contenu légèrement différent à chaque
  fois → un hash ne suffit pas). Fix : **dedup par CHEMIN** (`_WRITTEN`) — une 2e écriture du
  même fichier est **skippée** avec une directive qui matérialise l'état (« pom.xml déjà
  écrit, fichiers = [...], écris le suivant ou compile »). → débloque : il passe enfin de
  `pom.xml` à `FpmlToCdmApp.java`.
- Nudge **générique** + `_STALL_LIMIT` 3→2 + prompt « un fichier par tour, enchaîne ».

**Résultat** : progrès net vs baselines (lit les bons fichiers, brise la fixation pom.xml,
écrit 2 fichiers distincts). **Mais mur dur** : il **cale sur le transformer** (le fichier
qui demande le vrai mapping FpML→CDM). Le trace montre qu'il énonce « next is the
transformer » puis n'émet pas le tool call (198 chars, 0 call, ~90s/step). **Limite de
capacité du 9B, pas du harnais.**

**Prochains leviers (capacité, pas harnais)** :
- **Few-shot / template** : injecter un transformer FpML→CDM déjà écrit (depuis
  `knowledge_base/` ou un exemple `main`) à adapter, plutôt que d'écrire de zéro.
- **Découpe forcée du transformer** en petites méthodes via sous-agents dédiés.
- **Modèle plus capable** : vllm qwen-27B.

## 10. Validation modèle capable + diagnostic « mur API » (run DeepSeek)

Test de capacité via **OpenRouter / DeepSeek V4 Flash** (backend `openrouter`) sur
`ird-ex08-fra` → `workspaces/val-fra-deepseek`. Résultat décisif :
- DeepSeek **franchit le mur du 9B** : écrit pom + main + SemanticDiff + le transformer
  COMPLET (24k chars), compile, spawn un sous-agent fixer. Le harnais est donc **sain** ;
  le blocage 9B était une **limite de capacité du modèle**, pas du harnais.
- MAIS DeepSeek **ne converge pas non plus** : compile bloqué à **87 → 93 → 93 erreurs**,
  jamais atteint `run_test`. Il **ré-hallucine la même API CDM** à chaque réécriture
  (noms de builders/enums inventés) car **rien dans le harnais ne lui donne la
  vérité-terrain de l'API**. Coût ~0,06 $ (DeepSeek = dirt cheap). Run tué (tournait en rond).

→ **Vrai goulot identifié** : connaissance de l'API CDM 6.19, commune aux deux modèles.

## 11. Cheat-sheet API CDM 6.19 (levier #1) — `knowledge_base/reference/cdm_api.md`

Nouveau fichier de référence, **distillé du transformer qui COMPILE**
(`reference/example/IrsTransformer.java`) + **vérifié contre le jar 6.19**
(`javap` : `PeriodExtendedEnum`, `DayCountFractionEnum._30_360/ACT_360/ACT_ACT_ISDA` OK).
Contenu (grep-friendly, ancré par symbole) :
- Golden rules builder (`builder()`/`build()`/`toBuilder()`, `setX` vs `addX`, wrappers).
- Graphe d'objets top-level (TradeState → Trade → product/tradeLot/counterparty/party).
- **Wrappers meta** (`FieldWithMetaXxx`, `ReferenceWithMetaXxx`, `Reference`/`Key`/`MetaFields`)
  + helpers `addressRef`/`locationMeta` verbatim.
- **Piège n°1 : `PeriodEnum` vs `PeriodExtendedEnum`** (tableau par conteneur).
- Mangling enums (FpML string → constante CDM) + switch `parseDayCount` verbatim.
- Sous-objets récurrents (Counterparty, Party, RateSpecification fixed/floating,
  BusinessDayAdjustments, PriceQuantity/TradeLot…), primitives date/nombre, helpers XML,
  bloc d'imports 6.19 réels, et un protocole « quand ça compile pas » (lire file:line,
  grep le symbole, `javap` l'enum, jamais deviner).

**Câblage harnais** (`agent/autonomous.py`) : nouvelle section *« CDM 6.19 API reference
(READ THIS BEFORE WRITING THE TRANSFORMER) »* dans `SYSTEM_PROMPT_TEMPLATE`, pointant le
chemin absolu via le placeholder `{api_cheatsheet}` (résolu dans `.format(...)`). Le modèle
est invité à `read_file` une fois + `grep` par symbole sur erreur de compile. Smoke-test
`.format()` OK (4376 chars, référence présente). Objectif : faire tomber le plateau 93 err.

**RÉSULTAT VALIDÉ** (run `workspaces/val-fra-cheatsheet`, DeepSeek + cheat-sheet) :
le modèle **lit `cdm_api.md` dès le step 2**, puis fait fondre les erreurs de compile —
`57 → 43 → 23 → 11 → 9 → 0 (compile OK)` — là où la baseline **plafonnait à 93 sans
jamais compiler**. Puis atteint `run_test` et grimpe à **score 89,1 %** de correspondance
sémantique. Première fois qu'un modèle compile + teste le transformer FRA. Le mur est
passé de « ça compile pas » à « le mapping n'est pas encore 100 % correct ».

## 12. Tavily exposé + prompt restructuré en PHASES + nudge phase-aware

Suite à la validation, restructuration du raisonnement de l'agent (pattern
orchestrateur-worker confirmé par recherche web : *plan d'abord, puis déléguer aux
workers*).

**(a) Tavily exposé** (`agent/tools_registry.py` + `_ORCHESTRATOR_TOOLS`) : ajout de
`tavily_search` + `tavily_extract` au registry et au set orchestrateur (PAS aux
sous-agents, qui restent lean/focalisés code). Les 3 autres outils Tavily chargés
(`crawl`/`map`/`research`) restent non exposés (risque de dérive tokens). Noms réels
récupérés par introspection live du serveur MCP. → l'agent peut chercher la doc CDM/FpML
en ligne en **dernier recours** (le cheat-sheet d'abord).

**(b) SYSTEM_PROMPT restructuré en 5 phases ordonnées** (`SYSTEM_PROMPT_TEMPLATE`) :
- **Rôle = ORCHESTRATEUR** (n'écrit pas le transformer lui-même).
- **Phase 1 RECHERCHE** : lit FpML/JSON/exemple/cheat-sheet, identifie en quoi le produit
  diffère de l'exemple ; tavily seulement si non couvert.
- **Phase 2 PLAN** : écrit `{{project_dir}}/plan.md` détaillé — liste des fichiers + pour
  CHAQUE méthode du transformer une ligne
  `methodName(sig) → reads <FpML> → builds <CDM type> → cheat-sheet: <symbols>`, +
  marquage des méthodes indépendantes (parallélisables). C'est le **contrat**.
- **Phase 3 SCAFFOLD** : boilerplate verbatim + squelette du transformer (stubs `// TODO`).
- **Phase 4 IMPLEMENT** : `spawn_subagent` par méthode indépendante (en parallèle), brief
  précis (signature, chemin, éléments FpML, type CDM, symboles cheat-sheet).
- **Phase 5 COMPILE & TEST** : edit_file ciblé par erreur (jamais réécrire tout le
  fichier), boucle run_test → diffs → fix jusqu'à match=true.

**(c) Nudge phase-aware** (`_stall_nudge()` remplace la string statique `_WRITE_NUDGE`) :
le stall-guard (reads-only = no-progress) poussait avant vers `pom.xml`, ce qui aurait
**cassé les phases lecture-lourdes Recherche/Plan**. Désormais il inspecte `_WRITTEN` et
pousse vers la **phase suivante réelle** : pas de plan.md → « écris plan.md » ; plan.md
mais pas de pom.xml → « scaffold » ; scaffold présent → « edit_file/spawn/compile ».

Smoke-test OK : prompt 8637 chars, Tavily exposé (11 outils orchestrateur), 5 phases +
plan.md référencés, `_stall_nudge()` renvoie le bon message selon l'état. Placeholder
`{{project_dir}}` câblé dans `.format(...)`.

## 13. A/B prompt phasé vs cheat-sheet + feedback run_test compact

**A/B (FRA, DeepSeek, 1 run chacun)** — `val-fra-cheatsheet` (baseline) vs `val-fra-phased2` :

| Métrique | Baseline | Phasé+plan.md |
|---|---|---|
| best_score | **89,1** | 80,4 |
| 1er compile | 57 err | **19 err** |
| compiles → 0 | 8 (5 échecs) | **5 (2 échecs)** |
| tool_calls | 122 | **83** |
| durée | 1248 s | **690 s** |
| plan.md | non | oui |

Lecture : le prompt phasé est **bien plus efficace** (≈2× moins de tâtonnement, moitié du
temps, code initial 3× plus propre, planifie). Score final plus bas (80,4 vs 89,1) mais
**1 run stochastique chacun → écart en partie du bruit**, non concluant ; les gains
d'efficacité sont structurels. Aucun des deux n'atteint match=true.

**Incident infra (à connaître)** : `compile_project`/`run_test` tournent dans un conteneur
Docker. Si Docker tombe/redémarre, le validator **garde un id de conteneur mort** →
chaque compile renvoie *« container … is not running »*, que l'agent prend pour une erreur
de code et abandonne (a même émis un `<done>` faux). Fix : Docker up puis **redémarrer le
validator** (`start_servers.sh --stop` puis start) pour un conteneur frais. Le 1er
`val-fra-phased` a été invalidé par ça.

**Diagnostic corrigé du plafond (~80-89 %)** : contrairement à une 1re hypothèse, le modèle
**reçoit déjà le diff complet** — `run_test` renvoie `differences` (liste MISSING/EXTRA +
valeur attendue) + `score_detail` (matched/total, wrong_values). Il n'est ni aveugle ni
tronqué (`_cap_result` ne coupait que les `read_file`). Le plafond est une **vraie
difficulté de mapping CDM** (champs `adjustedDate`, nb d'identifiers, observable FRA).

**Levier livré — feedback run_test compact + observabilité** (`agent/autonomous.py`,
`agent/run_logger.py`) :
- `_compact_run_test()` (appelé par `_cap_result` pour `run_test`) transforme le JSON brut
  verbeux (**~13k chars**) en **liste de corrections priorisée (~2,8k, −78 %)** :
  `run_test: match=False score=80.4 (74/92 fields)` + `WRONG/MISSING/EXTRA/TYPE <path> …`
  + légende d'action. Court-circuit `match=true` (→ « emit <done> ») et `crash` (montre la
  stack avant les diffs). Plafond `_MAX_DIFF_LINES=40`. Fallback au brut si non-JSON.
  Gain de contexte critique pour la cible 16-32k local, et sortie plus actionnable.
- `run_logger.record_tool` parse enfin `differences`/`score_detail` → run.log montre
  `diffs=N M/T fields` au lieu de `diffs=None` (trou d'observabilité comblé).

Smoke-tests OK : import (syntaxe), 12992→2853 chars sur le vrai diff FRA (21 mismatches
préservés), logging `diffs=3 74/92 fields`, court-circuits match/crash, fallback non-JSON.

## 14. Audit « génération réelle vs code pré-mâché » + ablations + robustesse LLM

**Audit de provenance** (transformer FRA 89,1 %, `val-fra-cheatsheet`) : comparaison
méthode-par-méthode du transformer généré vs l'exemple swap + le cheat-sheet.
- **6 méthodes IDENTIQUES (copiées)** : toutes des helpers de plomberie (`addressRef`,
  `locationMeta`, `camelToSnake`, `first`, `all`, `text`).
- **10 ADAPTÉES** (existent dans l'exemple, corps réécrit pour la FRA) : `transform`,
  `buildProduct`, `buildEconomicTerms`, `buildCalculationPeriodDates`, `buildPaymentDates`,
  `buildTradeLot`, `buildTradeIdentifiers`, `buildParty`, `counterparty`, `parseDayCount`.
- **4 NOUVELLES (inventées)** : `buildFixedPayout`, `buildFloatingPayout`, `buildPaymentDate`,
  `buildResetDates`.
→ **70 % des méthodes (tout le mapping) sont adaptées ou inventées par le LLM** ; seuls les
6 helpers sont copiés. Le code n'est PAS « déjà tout prêt ». (Ligne à ligne : 48 % matchent
l'exemple, mais ce chiffre gonfle — bcp de lignes sont la seule forme valide de l'API.)

**Trace = audit fidèle** (`run_logger._cap_args`) : pour `write_file`/`edit_file`, les args
de code (`content`, `edits`, `new_string`, `newText`) sont gardés EN ENTIER dans
`trace.jsonl` (capés ailleurs) → on peut auditer le code exact émis par le LLM, tour par tour.

**Toggles d'ablation** (`--no-example`, `--no-cheatsheet`) : sections de prompt extraites en
constantes `EXAMPLE_SECTION`/`CHEATSHEET_SECTION`, injectées ou vidées selon le flag. Point
clé : le cheat-sheet **pointait** vers l'exemple → retirer la section ne suffit pas, le modèle
lisait le fichier quand même. Donc `--no-example` est rendu **étanche au niveau outil** :
`_BLOCK_EXAMPLE_READS` bloque tout `read_file` sur `reference/example/IrsTransformer.java`
(le template de mapping), mais PAS la boilerplate (pom/main/SemanticDiff = plomberie copiable).

**Ablation `--no-example`** (cheat-sheet seul, sans template) : 1ère tentative → transformer
FRA à **10 erreurs de compile au 1er jet** (vs 19-57 AVEC l'exemple) → **preuve que le modèle
génère vraiment depuis la guidance**, pas besoin du template. MAIS le run n'a pas convergé
(réécriture 23k→31k régressant à 49 err, édition à l'aveugle sans recompiler) PUIS **gel 43 min**
sur un appel LLM pendu → ablation non concluante, à refaire (`val-fra-noex-v2`).

**🐛 Bug critique corrigé — timeout LLM** (`agent/autonomous.py`) : `llm_call` n'avait **aucun
timeout** → une requête OpenRouter pendue figeait tout le run (43 min, 0 % CPU). Nouveau
`_llm_call_guarded()` : `asyncio.wait_for` **240 s** par appel, **3 tentatives** avec backoff
(2/4/8 s), log de chaque échec ; après épuisement → exception → run fini proprement en ERROR
(plus jamais de gel). Smoke-test OK.

**Note infra** : 3 hoquets cette session (serveurs MCP filesystem/validator + Docker tombés
entre runs ; `setsid` absent sur macOS). Relancer via `start_servers.sh` en tâche persistante
+ `docker info` avant chaque run. Toujours vérifier `Loaded 28 MCP tools total` au démarrage.

---

## 15. Refonte knowledge base prose pure + boilerplate-infra + 3 leviers de convergence (2026-06-12 → 06-15)

Exécution du réalignement (rapport §8). Synthèse : [`rapport.md`](rapport.md) §11 ; runs : [`iterations.md`](iterations.md) Loop 2.

### 15.1 Knowledge base — réécrit en PROSE, découpé par domaine
- Nouvelle arbo `knowledge_base/` : `README.md` (index routeur), `cdm/` (object-model,
  builder-conventions, meta-and-references, dates, enums, pitfalls + `structure-skeleton.json`,
  `rosetta/`, `hierarchy.txt`), `fpml/` (document-structure + rates + 13 stubs familles),
  `mapping/` (principles + rates + 13 stubs), `build/dependencies.md`, `policies/`, `notes/`.
- **Supprimés** (code Java copiable / contenu CDM-5.x faux) : `reference/cdm_api.md`,
  `reference/cdm/{global_key_guide,date_handling,enum_mappings}.md`, `rules/{disambiguation,irs}.md`.
  Contenu migré en prose ; **corrections** : globalKey est *calculé* (pas un UUID posé main),
  modèle de référence price/quantity = `address`↔`location` scope DOCUMENT, parties via
  `externalReference`=href FpML. **Pourquoi** : contrainte durcie « jamais de `.java` de mapping
  fourni » ; et un KB navigable par titre (l'IA ouvre le bon fichier précis, grep, cdm_lookup pour
  les signatures). Vérifié : 0 bloc Java copiable hors rosetta/hierarchy.
- `cdm_data_path_tree.json` → renommé `cdm/structure-skeleton.json` et mis en avant (carte des
  chemins JSON exacts ; le fichier que l'utilisateur a désigné comme « qui pourrait aider »).

### 15.2 `build/dependencies.md` corrigé (bug introduit puis fixé)
Le set prouvé (run à 100 % `val-fra-consolidation`) : `org.finos.cdm:cdm-java:6.19.0` (**Maven
Central, pas de repo custom**) + jackson-databind/jsr310 2.17.2 + **`com.google.inject:guice:6.0.0`**
(requis au runtime par `RosettaObjectMapper`) + assembly-plugin. **Retrait** de `rosetta-common:9.27.0`
(introuvable → cassait le compile au step 17 du v1) et du repo regnosys (inutile). Java 17.

### 15.3 Boilerplate pré-staged (`scaffold/` + `_prestage_boilerplate`)
`scaffold/` = `pom.xml` + `FpmlToCdmApp.java` (main : XML→`new IrsTransformer().transform(doc)`→
RosettaObjectMapper→SemanticDiff) + `SemanticDiff.java`, repris **verbatim** du run à 100 %.
`_prestage_boilerplate(project_dir)` les copie au démarrage ; marqués `boilerplate` dans `_WRITTEN`
(le write-guard bloque toute réécriture). Le modèle n'écrit QUE `IrsTransformer.java`
(contrat `public cdm.event.common.TradeState transform(org.w3c.dom.Document doc)`). Flag `--no-boilerplate`.
**Pourquoi** : infra ≠ mapping (conforme contrainte) ; concentre 100 % du budget sur le mapping.

### 15.4 Garde de cadence (`_COMPILE_CADENCE_LIMIT=3`, `turns_since_compile`)
Une fois le transformer écrit (`_transformer_written()`), force un `compile_project` après N tours
**sans** compiler — compte **chaque** tour (recherche incluse), contrairement au drift guard (edits
seuls). **Pourquoi** : mode d'échec dominant = rester à 1 erreur d'un build propre en sur-recherchant
18 tours (v3 : 109 cdm_lookup / 3 compiles). `_stall_nudge()` rendu transformer/boilerplate-aware
(transformer écrit → pousse compile→fix, plus plan/scaffold). Effet : v3 → v4 (3→10 compiles, 0→4 tests).

### 15.5 run_test bulletproof (override harnais)
`execute_tool` force `{project_dir, fpml_file, expected_json_file}` depuis les vrais chemins de la
tâche (`_TASK_FPML`/`_TASK_EXPECTED` posés par `main()`). **Pourquoi** : le modèle hallucinait les
chemins (`src/test/resources/FRA.xml`…) → 3/4 tests « file not found ». Effet : v4 (tests invalides)
→ v6 (premiers scores réels). Idem `project_dir` forcé sur `compile_project`.

### 15.6 Garde anti-`// TODO` — TESTÉE PUIS REVERTÉE (anti-pattern)
v5 : garde bloquant run_test tant qu'il restait des `// TODO` → **deadlock** (le modèle remplit sans
jamais tester, 0 test, 315 tool-calls). **Leçon** : tester un transformer partiel est **utile** — le
diff `MISSING x` EST la checklist de remplissage. Retirée ; Phase 5 du prompt réécrite « teste tôt,
le diff missing = ta checklist ». Effet : v5 (0 test) → v6 (5 tests, 22,8 %).

### 15.7 Prompt (`SYSTEM_PROMPT_TEMPLATE`) recâblé sur le KB
`EXAMPLE_SECTION`+`CHEATSHEET_SECTION` → une `KB_SECTION` pointant `knowledge_base/README.md` +
`structure-skeleton.json` + `cdm_lookup`. Section « build already set up » (boilerplate fourni, écris
ONLY le transformer). Phases 1-5 réécrites (recherche KB, plan transformer-only, scaffold+compile,
délégation, compile/test). Retrait plomberie morte de l'exemple (`_BLOCK_EXAMPLE_READS`,
`--no-example`) ; ablation renommée `--no-kb`. Smoke-tests OK (import, `.format()`, prestage, override).

### 15.8 Résultat
**Premier score réel sur un KB 100 % prose : FRA 22,8 % (21/92), 0→22,8.** Plateau = cœur économique
(`product`/`tradeLot`) laissé en stub (mapping nesté dur, fait seul). Harnais robuste & conforme.
Backend laissé sur `openrouter`/deepseek-v4-flash. **Pause** décidée à ce palier.
