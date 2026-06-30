import { tool } from "@opencode-ai/plugin"
import { execSync } from "node:child_process"

/**
 * category-report — score pass/fail par catégorie via io.fpmlcdm.CategoryReport.
 *
 * Donne, pour chaque catégorie demandée, le ratio pass/total et la liste des paires en
 * échec triées par nombre de diffs. Idéal pour cibler le travail après un changement.
 */

const MVN = process.env.OPENCODE_MVN || "C:\\Maven\\maven-3.9.12-takari\\bin\\mvn.cmd"

export default tool({
  description:
    "Score pass/fail par catégorie (io.fpmlcdm.CategoryReport) + liste des paires en échec " +
    "triées par taille de diff. Donner une ou plusieurs catégories de data/train.",
  args: {
    categories: tool.schema
      .array(tool.schema.string())
      .describe("Catégories sous data/train, ex: ['rates-5-10','credit-derivatives-5-13']"),
  },
  async execute(args, context) {
    const cmd =
      `"${MVN}" -q exec:java -Dexec.mainClass=io.fpmlcdm.CategoryReport ` +
      `-Dexec.args="${args.categories.join(" ")}"`
    try {
      return execSync(cmd, {
        cwd: context.directory,
        encoding: "utf8",
        maxBuffer: 32 * 1024 * 1024,
        timeout: 15 * 60 * 1000,
      })
    } catch (e: any) {
      return `ÉCHEC.\n${((e.stdout || "") + (e.stderr || "")).slice(-4000)}`
    }
  },
})
