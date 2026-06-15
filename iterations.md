# Journal des exécutions — pipeline FpML→CDM (interface_gen)

Rapport des runs de l'agent autonome sur la conversion FRA `ird-ex08-fra.xml` → CDM 6.19.
Modèle de dev : **DeepSeek V4 Flash** via OpenRouter (données FINOS publiques).
Critère de succès : `run_test` rapporte `match=true` (le JSON produit == JSON attendu sous
les règles `SemanticDiff`). Détails techniques dans [`modif.md`](modif.md).

> **Mise à jour** : un run amélioré à `--max-iter 120` (`val-fra-v3-120`) est en cours au
> moment de la rédaction — c'est le **premier** à bénéficier du feedback `run_test` compact,
> du drift guard et du timeout LLM. Résultat à compléter en fin de section.

---

## Tableau récapitulatif

| Run | Config | Compile (trajectoire) | Score | Statut | Verdict |
|---|---|---|---|---|---|
| `val-fra-deepseek` | **sans** cheat-sheet | 87→93→93 — **jamais 0** | — | tué | 🧱 mur API : hallucine les builders CDM |
| `val-fra-cheatsheet` | cheat-sheet, prompt simple | 57→43→23→11→9→**0** | **89,1 %** | MAX_ITER (60) | ✅ **breakthrough** : 1er compile propre + test atteint |
| `val-fra-phased` (1) | prompt phasé | "1" (factice) | — | SUCCESS (faux) | ⚠️ saboté infra (conteneur Docker mort) → `<done>` menteur |
| `val-fra-phased2` | prompt phasé, propre | 19→9→**0** | 80,4 % | MAX_ITER (60) | ✅ +efficace (3 compiles, 690 s) ; score bruité (1 run) |
| `val-fra-noexample` / `2` | ablation `--no-example` | infra HS | — | invalides | filesystem skippé / fuite exemple |
| `val-fra-noexample3` | ablation `--no-example` | 10 err → régresse 49 | — | gel 43 min | non concluant (appel LLM pendu) |
| `val-fra-noex-v2` | ablation `--no-example`, propre | 107→125→163→105→105 — **jamais 0** | — | MAX_ITER (60) | ✅ propre : **sans exemple → ne converge pas** |

### Autres modèles (runs antérieurs)
| Run | Modèle | Résultat |
|---|---|---|
| `run-fra-qwopus-v2` | Qwopus-9B | 120 iters, **0 compile, 0 test** → trop faible pour la boucle |
| `val-fra-qwen35` | qwen3.5-9B | 39→9→**0** compile mais **score None** → compile mais mapping cassé |
| `val-fra-lean4` | — | 0 outil utile → échec |

---

## Les 3 conclusions

### 1. Le cheat-sheet API CDM est LE levier
Sans lui : jamais de compilation (plateau 93 erreurs, hallucination des noms de builders).
Avec lui : compile propre (→0) + 89 % de correspondance. C'est de la **guidance
load-bearing** (seulement 3 % du code final en est copié verbatim), pas un fallback.

### 2. L'exemple est un ancrage structurel load-bearing (correction honnête)
Lecture initiale trop optimiste (« 10 erreurs au 1er jet sans exemple → le modèle se
débrouille »). Le run propre `val-fra-noex-v2` **dément** : **sans l'exemple, le modèle ne
converge pas** (105-163 erreurs, jamais compilé). Donc, les deux à la fois :
> Le modèle **génère vraiment** le mapping (audit de provenance : **70 % des méthodes
> adaptées ou inventées**, seuls 6 helpers de plomberie copiés), **MAIS** l'exemple lui
> sert d'**ancrage structurel** — on l'enlève, la génération part en vrille. Ce n'est pas du
> copier-coller de code pré-mâché ; c'est de la génération réelle *ancrée*.

### 3. Robustesse (objectif n°1)
- **Gel de 43 min** révélé → aucun timeout sur l'appel LLM. Corrigé : `_llm_call_guarded`
  (240 s/appel, 3 retries backoff, sinon run fini en ERROR — plus jamais de gel).
- **Édition à l'aveugle** (régression 10→49 en éditant sans recompiler) → **drift guard** :
  après 4 `write_file`/`edit_file` sans compile, nudge « compile_project NOW ».
- **Feedback `run_test` compact** : le JSON brut (~13 k chars) → liste de corrections
  priorisée (~2,8 k, −78 %) : `WRONG/MISSING/EXTRA/TYPE <path> …`. Le modèle recevait déjà
  le diff complet, mais verbeux ; désormais actionnable et économe en contexte (critique
  pour la cible 16-32k local).
- **3 hoquets d'infra** (serveurs MCP + Docker tombés entre runs) → relancer
  `start_servers.sh` en tâche persistante + `docker info` + vérifier `Loaded 28 MCP tools`.

---

## État du meilleur résultat
- **`val-fra-cheatsheet` : 89,1 %** — jamais `match=true`.
- Plafond restant = subtilités de mapping FRA : `tradeIdentifier` (2 vs 4 attendus),
  `adjustedDate` manquant (seul `unadjustedDate` posé), `FloatingRateIndex` (identifier /
  assetClass / indexTenor) incomplet, `meta.location` manquant.
- Le feedback `run_test` compact + 120 itérations doivent aider à franchir ce plateau.

## Run en cours — `val-fra-v3-120` (processus amélioré)
Config : exemple + cheat-sheet + prompt phasé + **max-iter 120**, premier run avec feedback
compact + drift guard + timeout LLM.
**Hypothèses testées** : (a) franchir 89,1 % grâce au diff actionnable, (b) moins de
tâtonnement grâce au drift guard, (c) plus de marge (120 iters) pour boucler le mapping.

**Résultat : RÉGRESSION.** status MAX_ITER, **best_score null** (jamais atteint run_test),
compiles 0→27→27→13→15 (compile propre tôt puis **casse tout en sur-éditant**), **stall
guard tiré 49×** (churning massif), drift 4×, llm-retry 0. Le modèle compile à 0 vers le
step 10 mais **ne lance pas le test**, continue d'éditer, casse la compilation et tourne en
rond 110 iters. → À 120 iters le prompt phasé pousse à la sur-édition. Le meilleur reste
`val-fra-cheatsheet` (89,1 %). Pistes : (a) outil pour vérifier l'API avant d'éditer
(→ itération 1 `cdm_lookup`), (b) forcer un run_test dès le 1er compile à 0.

---

# Loop d'amélioration (auto-cadencé) — contrainte : JAMAIS de code pré-écrit

Protocole par itération : (1) une hypothèse nouvelle, (2) implémenter, (3) run + comparer
au meilleur, (4) garder/revert, (5) logger ici. Seuls leviers autorisés : **outils,
feedback, structure de prompt, garde-fous** — pas de snippet/exemple/squelette/mapping fourni.

## Itération 1 — outil d'introspection CDM interrogeable (`cdm_lookup`)
**Hypothèse** : le plafond et les régressions de compile viennent de noms d'API incertains.
Plutôt qu'un cheat-sheet écrit à la main (borderline « pré-fait »), donner au modèle un
**outil qui interroge le jar** : `cdm_lookup name=<Type>` → vraies signatures builder
`set*/add*` (ou constantes d'enum) extraites par `javap`. Le modèle **vérifie la vérité
lui-même** (comme le compilateur), aucun code fourni.
**Implémenté** : `_cdm_lookup()` dans `autonomous.py` (résout nom→FQN via `unzip`, `javap`
sur le `$Builder`/l'enum), exposé comme outil local orchestrateur + sous-agents, câblé dans
la règle « ne devine jamais — `cdm_lookup` d'abord ». Smoke-test OK : `TradeLot` →
`addPriceQuantity(PriceQuantity)`…, `DayCountFractionEnum` → `_30_360, ACT_360…`,
`PayerReceiver` → `setPayer(CounterpartyRoleEnum)`, nom inconnu → message gracieux.
**Run A/B** (`val-fra-cdmlookup`, max-iter 80) → **✅ KEEP**. **Nouveau meilleur : 90,2 %**
(bat 89,1 %). Surtout : **0 échec de compile sur 4** (0→0→0→0) — vs toutes les runs
précédentes qui régressaient. `cdm_lookup` appelé **12×** → le modèle vérifie l'API au lieu
d'halluciner → élimine le mode d'échec qui tuait `v3-120`. Gain mécanistique robuste (pas
juste le score). **→ cdm_lookup gardé. Meilleur score : 90,2 %.**
Effet secondaire observé : le modèle a émis `<done>` en clamant « 100% match » alors que
score=90,2 / match=False → **arrêt 20 iters trop tôt** (faux `<done>`). → motive l'itération 2.

## Itération 2 — garde-fou anti-faux-`<done>`
**Hypothèse** : le modèle s'arrête en clamant la réussite alors que `match=False` (vu en
it.1 ET sur `val-fra-phased`). Rejeter tout `<done>` tant que le dernier `run_test` n'est
pas `match=true` → force le modèle à dépenser son budget pour fixer les derniers diffs au
lieu de s'arrêter à ~90 %. C'est un garde-fou (autorisé), zéro code fourni.
**Implémenté** : `require_match_for_done` dans `_run_loop` (orchestrateur=ON, sous-agents=OFF),
tracker `last_test_match`, nudge `_FALSE_DONE_NUDGE` sur les 2 sorties `done`. Smoke-test OK.
**Run A/B** (`val-fra-falsedone`, max-iter 80) → **non concluant / KEEP par sûreté**. Le
garde-fou n'a **jamais fait surface** (0 rejet) : le modèle n'a pas faussement clamé `<done>`,
il a churné 80 iters à réparer une régression de compile (0→9→11→29→15→0) et n'a testé qu'**1×
à la fin** (`diffs=1`, très proche). best_score null. → Le garde-fou est logiquement correct
et corrige un bug confirmé (faux SUCCESS d'it.1) sans nuire ici → **gardé**, mais non exercé.

**Constat dominant après 5 runs** (89,1 / 80,4 / null / 90,2 / null) : **2/5 churent** sur le
même mode d'échec → *compile propre tôt → sur-édite le mapping → casse la compilation → passe
le budget à réparer au lieu de tester*. Le drift guard (4 edits) ne suffit pas (régression
jusqu'à 29 err). C'est LA cible de l'itération 3.

## Itération 3 — forcer run_test après un compile propre (anti-churn)
**Hypothèse** : le churn vient de ce que le modèle, après un compile à 0, **n'enregistre pas
son progrès par un run_test** : il enchaîne des edits « pour améliorer » et casse la compil.
Garde-fou : après un `compile_project` OK, **exiger un `run_test` avant tout nouvel edit** →
(a) verrouille un score mesurable (plus de best_score null), (b) donne le diff pour des
corrections CIBLÉES au lieu de réécritures à l'aveugle. Garde-fou pur, zéro code fourni.
**Implémenté** : flag `clean_compile_untested` dans `_run_loop` (set sur `compile_project`
ok, clear sur `run_test`), nudge `_RUN_TEST_NUDGE` quand le modèle édite après un compile
propre sans avoir testé. Smoke-test OK.
**Run A/B** (`val-fra-checkpoint`, max-iter 80) → **KEEP (fiabilité), score dans le bruit**.
89,1 % (ne bat pas 90,2). MAIS : **4 run_test** (vs 1-2), **2 échecs compile seulement**
(vs 4 pour les runs churny), **score réel atteint** (plus de null), `cdm_lookup` 18×. Le
guard a tiré 1× → impact direct faible mais le process est nettement plus stable (le modèle
teste, ne churne plus jusqu'à null). → gardé comme garde-fou de fiabilité.

## Bilan intermédiaire (3 itérations)
| Levier | Effet | Décision |
|---|---|---|
| `cdm_lookup` (outil) | **90,2 %**, 0 hallucination compile | ✅ KEEP — gain clair |
| anti-faux-`<done>` (garde-fou) | corrige un bug réel (faux SUCCESS), non exercé | ✅ KEEP — correct |
| checkpoint run_test (garde-fou) | +fiabilité (4 tests, moins de churn), score = bruit | ✅ KEEP — stabilité |

**Meilleur : 90,2 %**, jamais match=true. Obstacle restant = **variance run-à-run élevée**
(80-90 %, parfois churn→null) + ~10 diffs FRA tenaces (`adjustedDate`, `tradeIdentifier`
2 vs 4, champs `FloatingRateIndex`, `meta.location`). Tous les leviers respectent la
contrainte (outils + garde-fous, zéro code fourni).

## Itération 4 — consolidation (config complète, max-iter 100)
**But** : mesurer le plafond des 3 leviers ENSEMBLE (chacun testé isolément dans du bruit) et
voir si une bonne trajectoire atteint match=true. Pas un nouveau levier — un point de mesure
propre avant de décider la suite. **Run** : `val-fra-consolidation`.

### 🎉 RÉSULTAT : match=true — 100,0 % (92/92 champs, 0 diff) — PREMIÈRE CONVERGENCE COMPLÈTE
status SUCCESS, best_score **100.0**, 7 tests, 1 seul échec compile. Progression **propre
sans churn** : 0 → 88,0 → 95,7 → **100,0**. `<done>` émis seulement à match=true réel
(anti-faux-done : REJECTED 0, plus besoin). `cdm_lookup` 7×, checkpoint guard 0× (le modèle
a testé de lui-même, 7×). **Les leviers composent** : pas d'hallucination d'API (cdm_lookup)
+ tests fréquents qui verrouillent le progrès + pas d'arrêt prématuré → montée nette jusqu'à
100 %. La pipeline PEUT désormais résoudre entièrement la FRA, zéro code fourni au modèle.

> ⚠️ Variance : d'autres runs de config proche ont fait 89-90 % ou churné. Le 100 % prouve
> la **capacité** ; pour la **robustesse** il faudrait 2-3/3 runs au but. Mais le levier
> décisif (`cdm_lookup`) + les garde-fous ont rendu cette convergence possible et propre.

## Synthèse — ce qui aide vraiment (tout = outils/feedback/structure, JAMAIS de code fourni)
1. **`cdm_lookup`** (outil d'introspection du jar) — LE levier : supprime l'hallucination
   d'API → 0 erreur de compile inventée. Sans lui : plateau 93 err, jamais de compile.
2. **Feedback `run_test` compact** (diff priorisé MISSING/EXTRA/WRONG + valeur attendue) —
   le modèle corrige ciblé, pas à l'aveugle.
3. **Garde-fous** : anti-faux-`<done>` (pas de faux SUCCESS), checkpoint run_test +
   drift guard (anti-churn : teste avant de sur-éditer), timeout LLM (anti-gel).
4. **Exemple + cheat-sheet** = ancrage structurel load-bearing (ablation : sans eux, ne
   converge pas) — mais ce sont de la *référence*, pas du mapping pré-écrit (70 % des
   méthodes sont générées/adaptées par le modèle).

## Itération 5 — généralisation à un autre produit (USD-OIS)
**But** : la *même pipeline inchangée* sur un produit différent (overnight index swap).
**Résultat : ÉCHEC (0,0).** Le programme **compile mais crashe à l'exécution**
(`NullPointerException`), donc produit une sortie vide → score 0, et le modèle n'a testé
qu'1× en 100 iters (churn sur la compile). → la pipeline **ne généralise pas
automatiquement** : chaque produit est un nouveau défi de mapping. **Cause identifiée** : le
feedback crash montrait `CRASH: NullPointerException` SANS localisation → le modèle ne savait
pas OÙ corriger. Trou de feedback → itération 6.

## Itération 6 — feedback crash localisé
**Hypothèse** : un NPE sans localisation est infixable ; le validator extrait pourtant déjà
`method/file/line` (la dernière frame `com.example.*`), mais `_compact_run_test` les noyait
dans un `str(crash)[:500]`. Exposer **`exception @ method (file:line) ← fix THIS line`** +
guidance (« un champ est null : garde-le ou set-le »). Feedback pur, zéro code fourni.
**Implémenté** : branch crash de `_compact_run_test` réécrit, gère le cas avec/sans
localisation. Smoke-test OK. **Run A/B** (`val-ois-crashfb`, USD-OIS, max-iter 100) :
_en cours._

---

# Loop 2 — refonte KB prose pure + convergence FRA (2026-06-12 → 06-15)

Contexte : KB refondu **100 % prose** (zéro `.java` de mapping fourni), boilerplate pré-staged.
Modèle **DeepSeek-v4-flash** (`openrouter` ; qwopus/GPU LAN injoignable). Cible FRA `ird-ex08-fra`.
Synthèse consolidée dans [`rapport.md`](rapport.md) §11 ; détail technique dans [`modif.md`](modif.md) §15.

| Run | workspace | levier ajouté | compiles | run_test | best | verdict |
|----|-----------|---------------|----------|----------|------|---------|
| v1 | `val-fra-newkb` | KB prose seul | 3 éch. | 0 | — | 108 err, jamais compilé |
| v2 | `val-fra-newkb-v2` | + fix deps (rosetta-common faux → guice) | 3 éch. | 0 | — | jamais compilé |
| v3 | `val-fra-boilerplate` | + boilerplate pré-staged | 3 | 0 | — | transformer 21 KB → **1 erreur** (import `org.w3c.dom.Element` manquant), jamais recompilé (109 cdm_lookup / 3 compiles) |
| v4 | `val-fra-cadence` | + garde de cadence | 10 | **4** | 0.0 | **mur cassé** : 1er run_test atteint ; mais run_test mal appelé (3/4 « file not found ») + stubs |
| v5 | `val-fra-v5` | + garde anti-`// TODO` | 6 | 0 | — | **régression** : la garde « ne teste pas tant que TODO » → remplit sans jamais tester (deadlock, 315 tool-calls) → **revert** |
| **v6** | `val-fra-v6` | + run_test bulletproof, − garde TODO | **12** | **5** | **22.8** | **premier score réel : 0 → 22,8 % (21/92)** |

## Leçons Loop 2
1. **Boilerplate** = ancrage structurel ⇒ v2 (0 compile) → v3 (1 erreur). Conforme contrainte (infra ≠ mapping).
2. **Cadence forcée** (compte la recherche, pas que les edits) ⇒ casse « sur-recherche → jamais compiler » : v3 → v4 (4 run_test).
3. **run_test override harnais** (force les vrais chemins) ⇒ tests valides ⇒ v6 score réel.
4. **Anti-pattern** : bloquer le test tant qu'il reste des TODO ⇒ deadlock. Tester partiel est utile (le diff `MISSING x` = la checklist).

## Plateau & suite
Plateau v6 = **22,8 %** : `MISSING trade.product` + `MISSING trade.tradeLot`, 21 `// TODO` restants
(cœur économique laissé en stub). Le modèle fait le scaffold facile mais cale sur le mapping nesté
(payouts/tradeLot) seul. **Prochains leviers** : délégation sous-agents des méthodes dures · +iters
+ diff granulaire · modèle plus capable / prose mapping étoffée. **Décision 2026-06-15 : pause.**
