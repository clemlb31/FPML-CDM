import { tool } from "@opencode-ai/plugin"
import { execSync } from "node:child_process"

/**
 * run-dataset-tests — lance DataDrivenValidationTest avec les bons flags.
 *
 * Applique la règle AGENTS.md : toute feature se valide sur le dataset complet.
 * Encapsule l'incantation mvn exacte (que le modèle retient mal) et utilise le bon
 * mvn (pas sur le PATH par défaut). Surcharge possible via OPENCODE_MVN.
 */

const MVN = process.env.OPENCODE_MVN || "C:\\Maven\\maven-3.9.12-takari\\bin\\mvn.cmd"

export default tool({
  description:
    "Lance la suite FpML→CDM (DataDrivenValidationTest) sur le dataset data/train et " +
    "renvoie le résumé Surefire (Tests run / failures). includeIncomplete=true par défaut " +
    "(530 paires) ; method permet de cibler un seul signal.",
  args: {
    includeIncomplete: tool.schema
      .boolean()
      .optional()
      .describe("Inclure *-incomplete (530 paires). Défaut true ; false = 360 paires curatées."),
    method: tool.schema
      .enum(["semanticallyEqual", "noNewCdmViolations", "globalKeyIntegrity"])
      .optional()
      .describe("Cibler un seul des 3 signaux de validation."),
  },
  async execute(args, context) {
    const incomplete = args.includeIncomplete !== false
    const testSel = args.method
      ? `DataDrivenValidationTest#${args.method}`
      : "DataDrivenValidationTest"
    const cmd =
      `"${MVN}" -q -B test -Dtest=${testSel}` +
      (incomplete ? " -Dincludeincomplete=true" : "")
    try {
      const out = execSync(cmd, {
        cwd: context.directory,
        encoding: "utf8",
        maxBuffer: 64 * 1024 * 1024,
        timeout: 20 * 60 * 1000,
      })
      const summary = out.split("\n").filter((l) => /Tests run|BUILD|ERROR/.test(l)).join("\n")
      return summary || out.slice(-4000)
    } catch (e: any) {
      const log = (e.stdout || "") + (e.stderr || "")
      const tail = log.split("\n").filter((l: string) => /Tests run|BUILD|ERROR|\.java:|No versions/.test(l))
      // Indice fréquent : build par défaut bloqué car le mirror Murex est injoignable.
      const hint = /No versions available/.test(log)
        ? "\n\nIndice: résolution de deps bloquée — le mvn -takari pointe le Nexus Murex (injoignable hors VPN). Utilise un settings.xml Maven Central ou reconnecte-toi au Nexus."
        : ""
      return `ÉCHEC.\n${tail.slice(-50).join("\n")}${hint}`
    }
  },
})
