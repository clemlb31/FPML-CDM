import { tool } from "@opencode-ai/plugin"
import { execSync } from "node:child_process"
import { readdirSync, readFileSync } from "node:fs"
import { join } from "node:path"

/**
 * cdm-api — inspecte l'API CDM RÉELLE (signatures, builders, enums) via `javap`.
 *
 * Raison d'être : sur ce projet, la première cause de bugs est l'invention de classes
 * ou de méthodes CDM qui n'existent pas dans la version utilisée. Ce tool laisse le
 * modèle VÉRIFIER au lieu d'halluciner. À appeler AVANT d'écrire du code touchant une
 * classe CDM.
 *
 * Cible le jar `cdm-java` correspondant à <cdm.version> du pom.xml (6.19.0), pas un
 * autre. Sans accès au .m2, lève une erreur explicite.
 */

const HOME = process.env.USERPROFILE || process.env.HOME || ""
const JAVAP = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", "javap") : "javap"

function pomCdmVersion(dir: string): string {
  try {
    const pom = readFileSync(join(dir, "pom.xml"), "utf8")
    return pom.match(/<cdm\.version>([^<]+)</)?.[1] ?? "6.19.0"
  } catch {
    return "6.19.0"
  }
}

function findCdmJar(version: string): string {
  const base = join(HOME, ".m2", "repository", "org", "finos", "cdm", "cdm-java")
  let all: string[]
  try {
    all = (readdirSync(base, { recursive: true }) as string[])
      .filter((f) => f.endsWith(".jar") && !f.includes("sources"))
      .map((f) => join(base, f))
  } catch {
    throw new Error(`Pas de cdm-java dans ${base} — accès .m2/Nexus requis.`)
  }
  return all.find((p) => p.includes(`${version}`)) ?? all.sort().pop() ?? ""
}

export default tool({
  description:
    "Inspecte l'API CDM réelle (classe, builder, enum) via javap. À APPELER avant " +
    "d'utiliser une classe/méthode CDM, pour ne pas en inventer. Accepte un nom simple " +
    "(InterestRatePayout) ou pleinement qualifié (cdm.product.asset.InterestRatePayout).",
  args: {
    symbol: tool.schema
      .string()
      .describe("Nom de classe/enum CDM, simple ou pleinement qualifié."),
  },
  async execute(args, context) {
    const version = pomCdmVersion(context.directory)
    const jar = findCdmJar(version)
    if (!jar) throw new Error("cdm-java jar introuvable dans le .m2.")

    const run = (fqn: string) =>
      execSync(`"${JAVAP}" -classpath "${jar}" ${fqn}`, {
        encoding: "utf8",
        maxBuffer: 8 * 1024 * 1024,
      })

    // FQN fourni -> javap direct
    if (args.symbol.includes(".")) {
      try {
        return `// ${jar}\n${run(args.symbol)}`
      } catch {
        /* tombe dans la recherche ci-dessous */
      }
    }

    // Nom simple -> on cherche la (les) classe(s) correspondante(s) dans l'index du jar
    const simple = args.symbol.replace(/.*\./, "")
    const matches = execSync(`"${JAVAP.replace("javap", "jar")}" tf "${jar}"`, {
      encoding: "utf8",
      maxBuffer: 32 * 1024 * 1024,
    })
      .split("\n")
      .filter((l) => l.endsWith(`/${simple}.class`))
      .map((l) => l.replace(/\.class$/, "").replace(/\//g, "."))

    if (matches.length === 0) {
      return `Aucune classe "${simple}" dans cdm-java ${version}. Vérifie le nom (et regarde knowledge_base/fpml-cdm/knowledge/cdm_api_quirks.md §3 pour les déplacements de package fréquents).`
    }
    // javap sur les premiers matches (souvent 1 seul)
    const out = matches
      .slice(0, 3)
      .map((fqn) => {
        try {
          return `// ${fqn}\n${run(fqn)}`
        } catch (e) {
          return `// ${fqn} (javap a échoué: ${(e as Error).message})`
        }
      })
      .join("\n\n")
    const more = matches.length > 3 ? `\n\n(+${matches.length - 3} autres candidats)` : ""
    return `// jar: ${jar}\n\n${out}${more}`
  },
})
