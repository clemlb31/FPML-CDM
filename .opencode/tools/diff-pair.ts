import { tool } from "@opencode-ai/plugin"
import { execSync } from "node:child_process"

/**
 * diff-pair — diff sémantique sur UNE paire (category, base) via io.fpmlcdm.DiffOne.
 *
 * La boucle d'itération rapide quand un mapper échoue : convertit data/train/<cat>/fpml/<base>.xml,
 * compare au CDM JSON de référence, et imprime les diffs (ou EQUAL). Bien plus rapide que
 * relancer les 530 tests.
 */

const MVN = process.env.OPENCODE_MVN || "C:\\Maven\\maven-3.9.12-takari\\bin\\mvn.cmd"

export default tool({
  description:
    "Diff sémantique d'une seule paire FpML/CDM (io.fpmlcdm.DiffOne). Renvoie 'EQUAL' ou la " +
    "liste des diffs. Utiliser pour debugger un mapper sans relancer toute la suite.",
  args: {
    category: tool.schema.string().describe("Catégorie sous data/train, ex: rates-5-10"),
    base: tool.schema.string().describe("Nom de base du fichier sans extension, ex: ird-ex01-vanilla-swap"),
  },
  async execute(args, context) {
    const cmd =
      `"${MVN}" -q exec:java -Dexec.mainClass=io.fpmlcdm.DiffOne ` +
      `-Dexec.args="${args.category} ${args.base}"`
    try {
      return execSync(cmd, {
        cwd: context.directory,
        encoding: "utf8",
        maxBuffer: 32 * 1024 * 1024,
        timeout: 10 * 60 * 1000,
      })
    } catch (e: any) {
      return `ÉCHEC.\n${((e.stdout || "") + (e.stderr || "")).slice(-4000)}`
    }
  },
})
