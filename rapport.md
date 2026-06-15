# Rapport — pipeline autonome FpML → CDM (`interface_gen`)

Rapport consolidé de la session : objectif, modifications du harnais, toutes les exécutions,
l'audit « code généré vs pré-mâché », les itérations de la loop d'amélioration, et le
réalignement final. Compléments : [`modif.md`](modif.md) (détail technique des modifs) et
[`iterations.md`](iterations.md) (journal de la loop).

---

## 1. Contexte & objectif

Agent autonome (ReAct, `agent/autonomous.py`) qui convertit un document **FpML 5.x** en
**CDM 6.19 JSON** en écrivant, compilant et exécutant un programme Java — via des serveurs
MCP (filesystem, validator Docker/maven, grep, tavily). Modèle de dev : **DeepSeek V4 Flash**
(OpenRouter, données FINOS publiques). Succès = `run_test` rapporte `match=true` (JSON produit
== JSON attendu sous les règles `SemanticDiff`).

**Objectif n°1 (constant)** : une pipeline **robuste et compréhensible**, parties les plus
indépendantes possibles — au-dessus du pass-rate.

**Contrainte clarifiée (2026-06-11)** : ne **jamais donner de code pré-écrit au modèle**.
De la **documentation prose en `.md`** dans le knowledge base est OK ; du `.java` pré-écrit
(exemple, snippets copiables, squelette, mapping) ne l'est pas.

---

## 2. État d'entrée de session

Diagnostic initial : les modèles **9B locaux** (Qwopus-9B, qwen3.5-9B) sont **trop faibles**
pour la boucle agentique (calent, n'émettent pas le gros transformer). **DeepSeek** franchit
ce mur. Mais même DeepSeek **plafonnait à 93 erreurs de compilation, sans jamais compiler** —
il **hallucinait l'API CDM** (noms de builders/enums inventés). Le vrai goulot n'était pas le
harnais mais la **connaissance de l'API CDM 6.19**.

---

## 3. Modifications du harnais (cette session)

Toutes dans `agent/autonomous.py`, `agent/run_logger.py`, `agent/tools_registry.py`.
Classées par **type d'aide donnée au modèle** (la distinction centrale de la contrainte) :

### Outils (faits à la demande — conformes)
- **`cdm_lookup`** *(levier décisif)* : introspection du jar CDM via `javap`. Le modèle
  demande un type → reçoit les vraies signatures builder `set*/add*` (ou les constantes
  d'enum). Il **vérifie l'API au lieu d'halluciner**. → 0 erreur de compile inventée.
- `tavily_search` / `tavily_extract` : recherche web (dernier recours).

### Feedback (dit *quoi* est faux — conforme)
- **`_compact_run_test`** : le diff brut de `run_test` (~13 k chars de JSON) → **liste de
  corrections priorisée** (~2,8 k, −78 %) : `WRONG/MISSING/EXTRA/TYPE <path> + valeur
  attendue`. + court-circuit `match=true` et **feedback de crash localisé**
  (`exception @ method (file:line)`).
- `run_logger` : parse `differences`/`score_detail` (log `diffs=N M/T fields`) ; trace
  `write_file`/`edit_file` en **contenu complet** (audit fidèle du code émis).

### Garde-fous (disciplinent la boucle — conformes)
- **timeout + retry LLM** (`_llm_call_guarded`, 240 s, 3 essais) — anti-gel (un run avait
  gelé 43 min sur un appel pendu).
- **drift guard** : compile forcé après 4 `write_file`/`edit_file` (anti édition aveugle).
- **checkpoint guard** : `run_test` forcé après un compile propre (verrouille le score,
  évite la régression « compile→sur-édite→casse »).
- **anti-faux-`<done>`** (`require_match_for_done`) : rejette `<done>` tant que le dernier
  `run_test` n'est pas `match=true` (le modèle clamait « 100% » à 90%).
- **stall guard phase-aware** (`_stall_nudge`) : pousse vers la phase suivante réelle.

### Structure (prompt — conforme)
- Prompt restructuré en **5 phases** : Recherche → `plan.md` → Scaffold → Implémentation
  (sous-agents) → Compile/Test.

### Références (connaissance)
- `cdm_api.md` (cheat-sheet écrit main — **prose + snippets**, borderline). 🔴
- `example/IrsTransformer.java` (**transformer COMPLET** = code pré-écrit). 🔴 **à retirer.**
- Toggles d'ablation `--no-example` / `--no-cheatsheet` (étanches au niveau outil).

---

## 4. Toutes les exécutions (FRA `ird-ex08-fra`, DeepSeek)

| Run | Config | best | iters | tests | échecs compile | Verdict |
|---|---|---|---|---|---|---|
| `val-fra-deepseek` | sans cheat-sheet | — | — | 0 | — (93 err) | 🧱 mur API, jamais compilé |
| `val-fra-cheatsheet` | + cheat-sheet | **89,1** | 60 | 3 | 5 | ✅ 1ère convergence compile |
| `val-fra-phased2` | + prompt phasé | 80,4 | 60 | 3 | 2 | +efficace, score bruité |
| `val-fra-v3-120` | + feedback + guards, 120it | **null** | 120 | 0 | 4 | ❌ churn (compile→casse, jamais testé) |
| `val-fra-cdmlookup` | **+ cdm_lookup** | **90,2** | 60 | 2 | **0** | ✅ 0 hallucination — KEEP |
| `val-fra-falsedone` | + anti-faux-done | null | 80 | 1 | 4 | churn (guard non exercé) |
| `val-fra-checkpoint` | + checkpoint guard | 89,1 | 80 | 4 | 2 | +fiabilité (4 tests) |
| **`val-fra-consolidation`** | **config complète, 100it** | **100,0** 🎉 | 56 | 7 | 1 | **match=true — 92/92 champs** |

**Généralisation (autre produit)** :
| `val-ois-generalize` | USD-OIS, config FRA | 0,0 | 100 | 1 | 2 | ❌ crash runtime NPE |
| `val-ois-crashfb` | + feedback crash localisé | — | (coupé) | — | — | crash persistait |

**Modèles 9B (antérieurs)** : `run-fra-qwopus-v2` (Qwopus-9B) et `val-fra-qwen35`
(qwen3.5-9B) → 0 ou compile sans score. Trop faibles pour la boucle.

---

## 5. Audit « code généré par le LLM vs pré-mâché »

Question posée : le modèle **génère** vraiment le mapping, ou recopie-t-il du code prêt ?

**Provenance méthode-par-méthode** (transformer FRA généré vs l'exemple swap) :
- **6 méthodes IDENTIQUES (copiées)** = uniquement des helpers de plomberie (`first`, `all`,
  `text`, `addressRef`, `locationMeta`, `camelToSnake`).
- **10 ADAPTÉES** (existent dans l'exemple, corps réécrit pour la FRA).
- **4 INVENTÉES** (absentes de l'exemple : `buildFixedPayout`, `buildFloatingPayout`,
  `buildPaymentDate`, `buildResetDates`).
→ **70 % des méthodes (tout le mapping) générées/adaptées par le LLM.** Preuve dans la trace
(le modèle raisonne sur l'adaptation swap→FRA tour par tour). Le nom `buildFloatingLeg` n'est
nulle part dans le prompt/cheat-sheet/exemple → choisi par le modèle.

**MAIS — ablation `--no-example`** (sans le transformer-exemple) : le modèle **ne converge
pas** (105-163 erreurs, jamais compilé). Donc :
> Le modèle génère vraiment le mapping, **mais l'exemple est un ancrage structurel
> load-bearing**. C'est la seule pièce vraiment « pré-écrite » dont la pipeline dépend.

---

## 6. Loop d'amélioration auto-cadencée (contrainte : zéro code fourni)

Protocole : 1 hypothèse → implémenter → run → comparer → garder/revert → logger.

| It. | Levier | Résultat | Décision |
|---|---|---|---|
| 1 | `cdm_lookup` | **90,2 %**, 0 hallucination compile | ✅ KEEP (décisif) |
| 2 | anti-faux-`<done>` | non exercé mais corrige un bug réel | ✅ KEEP |
| 3 | checkpoint run_test | +fiabilité, score = bruit | ✅ KEEP |
| 4 | consolidation (3 leviers, 100it) | **🎉 100 % match=true** | — |
| 5 | généralisation USD-OIS | ❌ crash NPE | feedback à améliorer |
| 6 | feedback crash localisé | crash persistait (coupé) | — |

**Résultat majeur** : **la FRA atteint 100 % match=true** (première convergence complète),
montée propre 0 → 88 → 95,7 → 100, par composition des leviers (cdm_lookup + tests fréquents
+ pas d'arrêt prématuré). **Tout en respectant la contrainte côté leviers** (outils/feedback/
garde-fous, zéro code fourni).

> ⚠️ **Variance run-à-run élevée** : la même config a aussi fait 89-90 % ou churné→null. Le
> 100 % prouve la **capacité**, pas encore la **robustesse reproductible**.

---

## 7. Ce qui aide vraiment (synthèse)

1. **`cdm_lookup`** — LE levier : supprime l'hallucination d'API (0 erreur compile inventée).
2. **Feedback compact + localisé** — corrections ciblées, pas à l'aveugle.
3. **Garde-fous** — anti-churn, anti-faux-done, anti-gel : transforment une capacité
   instable en convergence (plus) fiable.
4. **Exemple + cheat-sheet** — ancrage load-bearing, **mais = le code pré-écrit à éliminer**.

---

## 8. Réalignement — ce qu'on veut maintenant (décisions 2026-06-11)

- **Objectif** : code harnais **robuste & compréhensible** + **zéro code pré-écrit** donné au
  modèle (doc **prose `.md`** OK).
- **Exemple-transformer** : **à retirer / remplacer** par de la doc prose + `cdm_lookup`.
- **Périmètre** : **FRA d'abord, à fond** (robuste/reproductible sur plusieurs runs) avant de
  généraliser.

### Plan
1. **Retirer le code pré-écrit** : supprimer `example/IrsTransformer.java` ; réécrire
   `cdm_api.md` en **prose** (décrire le graphe d'objets & patterns en mots, sans blocs
   copiables) ; les signatures exactes viennent de `cdm_lookup`.
2. **Boilerplate (pom/main/SemanticDiff)** — *décision en attente* : (a) le **harnais la
   pré-crée** comme infra (le modèle n'écrit QUE le mapping) [reco] ; (b) la documenter en
   prose ; (c) la garder en `.java`. Note : le `main` appelle `SemanticDiff.compare(...)` et
   instancie `IrsTransformer` (couplage à défaire si on retire l'exemple).
3. **Adapter le prompt** (retirer le pointeur « worked example », pointer la doc + cdm_lookup)
   et **consolider/clarifier les garde-fous** (objectif robustesse/compréhension).
4. **FRA à fond** : re-run SANS code pré-écrit → itérer la doc/outils jusqu'à `match=true`
   **reproductible** (2-3 runs).

---

## 9. État courant
- **Meilleur résultat : FRA 100 % match=true** (`val-fra-consolidation`), non encore reproductible.
- Tous les leviers (cdm_lookup, feedback, garde-fous) en place et conformes à la contrainte.
- **Reste le code pré-écrit** (exemple + snippets cheat-sheet) à retirer — chantier ouvert.
- Loop d'amélioration **arrêtée** (voir §10).

## 10. Comment arrêter / piloter la loop `/loop`
La loop en **mode dynamique** (sans intervalle) ne tourne **pas** via un cron : elle se
relance elle-même parce qu'à la fin de chaque tour j'appelle `ScheduleWakeup` (qui reprogramme
le prochain réveil). **Pour l'arrêter : il suffit que je n'appelle plus `ScheduleWakeup`** —
c'est le cas ici, donc elle est **éteinte**. Un réveil déjà programmé peut se déclencher une
dernière fois ; je ne le relancerai pas. (Pour une loop à intervalle fixe, ce serait un cron à
supprimer avec `CronDelete` — pas le cas ici.) Tu peux aussi toujours l'interrompre en tapant
un message.

---

## 11. Session refonte knowledge base (prose pure) + convergence FRA — 6 runs (2026-06-12 → 06-15)

Exécution du réalignement (§8) : KB refondu **100 % prose** (aucun `.java` de mapping fourni) puis
poussée de convergence FRA. Modèle : **DeepSeek-v4-flash** via `openrouter` (qwopus/GPU LAN
injoignable). Détail technique des modifs harnais dans [`modif.md`](modif.md) §15.

### 11.1 Refonte du knowledge base
- Réorganisé **par domaine**, fichiers courts mono-sujet, index `README.md` routeur :
  - `cdm/` (cible, prose) : object-model · builder-conventions · meta-and-references (**corrigé** :
    globalKey *calculé* pas UUID posé ; modèle `address`+scope=DOCUMENT pour price/quantity ;
    `externalReference`=href FpML pour parties) · dates · enums · pitfalls ; + `structure-skeleton.json`
    (TradeState à `null` = carte des chemins, ex-`cdm_data_path_tree.json`), `rosetta/`, `hierarchy.txt`.
  - `fpml/` (source) : document-structure + rates (profond) + 1 stub par famille (13).
  - `mapping/` (pont) : principles + rates (profond) + 1 stub par famille (13).
  - `build/dependencies.md` (corrigé : cdm-java 6.19 sur Maven Central + jackson + **guice 6.0.0**,
    plus de rosetta-common/regnosys), `policies/`, `notes/`.
- **Supprimés** (code copiable / périmé CDM-5.x) : `cdm_api.md`, `global_key_guide.md`,
  `disambiguation.md`, `date_handling.md`, `enum_mappings.md`, `rules/irs.md` — migrés en prose.
- Vérifié : **0 bloc Java copiable** dans le KB (hors `rosetta/`/`hierarchy.txt`).

### 11.2 Boilerplate pré-staged (décision §8 item 2 → option a)
`scaffold/` = pom + FpmlToCdmApp + SemanticDiff **prouvés** (infra ≠ mapping). `_prestage_boilerplate()`
les copie dans chaque `project_dir` → le modèle n'écrit QUE `IrsTransformer.java`
(contrat `public cdm.event.common.TradeState transform(org.w3c.dom.Document doc)`). `--no-boilerplate` ablate.

### 11.3 Les 6 runs FRA (`ird-ex08-fra`, DeepSeek-v4-flash, max-iter 100)

| Run | workspace | Levier ajouté | compiles | run_test | best | Verdict |
|----|-----------|---------------|----------|----------|------|---------|
| v1 | `val-fra-newkb` | KB prose seul | 3 (éch.) | 0 | — | 108 err, jamais compilé |
| v2 | `val-fra-newkb-v2` | + fix deps | 3 (éch.) | 0 | — | jamais compilé |
| v3 | `val-fra-boilerplate` | + boilerplate | 3 | 0 | — | transformer 21 KB → **1 erreur** (import manquant), jamais recompilé |
| v4 | `val-fra-cadence` | + garde de cadence | 10 | **4** | 0.0 | **mur cassé** : atteint run_test ; mais run_test mal appelé + stubs |
| v5 | `val-fra-v5` | + garde anti-TODO | 6 | 0 | — | **régression** (deadlock) → **revert** |
| **v6** | `val-fra-v6` | + run_test bulletproof, −garde TODO | **12** | **5** | **22.8** | **premier score réel : 0 → 22,8 % (21/92)** |

### 11.4 Les 3 leviers harnais décisifs
1. **Boilerplate pré-staged** → budget 100 % sur le mapping. v2 (0 compile) → v3 (1 erreur).
2. **Garde de cadence** (`_COMPILE_CADENCE_LIMIT=3`) : force compile après N tours sans compiler
   (compte la **recherche**, pas que les edits). v3 (3 compiles, 0 test) → v4 (10 compiles, 4 tests).
   Casse la sur-recherche (109 `cdm_lookup` / 3 compiles).
3. **run_test override** : le harnais force `{project_dir, fpml_file, expected_json_file}` (le modèle
   hallucinait les chemins → 3/4 tests « file not found »). v4 (tests invalides) → v6 (scores réels).
- **Anti-pattern appris** : une garde « bloque run_test tant qu'il reste des `// TODO` » (v5) crée un
  **deadlock** (remplit sans jamais tester). Tester partiel est UTILE : le diff « MISSING x » EST la
  checklist de remplissage. → revertée.

### 11.5 Plateau à 22,8 % (état courant)
Diff v6 : `MISSING trade.product` + `MISSING trade.tradeLot` ; **21 `// TODO` restants**. Le modèle
remplit le scaffold facile (header/parties/identifiers = 21 champs) mais **laisse en stub tout le
cœur économique** (`buildProduct`/`buildPayout`/`buildTradeLots` — modèle address/référence, rate
spec, calculation period dates). C'est le pattern « l'exemple était load-bearing » **un cran plus
profond** : il va plus loin (22,8 % vs 0 %) mais cale sur le mapping nesté, seul.

### 11.6 Bilan & prochains leviers
- **Acquis** : KB **100 % prose** (zéro code de mapping fourni) → pipeline qui **compile, teste et
  score (0 → 22,8 %)**. Harnais robuste et conforme à la contrainte. Le mur est passé de « ne
  compile pas » à « ne fait pas le mapping profond seul ».
- **Pour franchir le plateau** (par promesse) : (1) **délégation sous-agents** des méthodes dures
  (contexte frais ; design existant sous-utilisé) ; (2) **+ d'itérations + diff granulaire**
  (décomposer un subtree `MISSING`) ; (3) **modèle plus capable** / étoffer la prose mapping
  product/tradeLot.
- **Décision 2026-06-15 : PAUSE** à ce palier (acquis solide). Backend laissé sur
  `openrouter`/deepseek-v4-flash. Rien n'a été perdu : tout est consigné ici + dans la mémoire projet.
