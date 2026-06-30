import { tool } from "@opencode-ai/plugin"
import { execSync } from "node:child_process"
import { readdirSync, existsSync, mkdirSync, readFileSync } from "node:fs"
import { join } from "node:path"

/**
 * cdm-source — grep le CODE SOURCE réel de CDM (le .m2 contient cdm-java-<ver>-sources.jar).
 *
 * Complète cdm-api : là où `javap` donne les signatures, ce tool montre l'implémentation
 * réelle des builders / enums / valeurs. Utile pour comprendre comment construire un objet
 * correctement (ordre des setters, choix de wrapper, valeurs d'enum exactes).
 *
 * Extrait le jar de sources une fois dans tmp/cdm-sources/, puis grep en JS.
 */

const HOME = process.env.USERPROFILE || process.env.HOME || ""
const JARBIN = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", "jar") : "jar"

function pomCdmVersion(dir: string): string {
  try {
    return readFileSync(join(dir, "pom.xml"), "utf8").match(/<cdm\.version>([^<]+)</)?.[1] ?? "6.19.0"
  } catch {
    return "6.19.0"
  }
}

function ensureSources(dir: string, version: string): string {
  const cache = join(dir, "tmp", "cdm-sources")
  if (existsSync(join(cache, "cdm"))) return cache
  const base = join(HOME, ".m2", "repository", "org", "finos", "cdm", "cdm-java")
  const srcJar = (readdirSync(base, { recursive: true }) as string[])
    .filter((f) => f.endsWith("sources.jar") && f.includes(version))
    .map((f) => join(base, f))
    .sort()
    .pop()
  if (!srcJar) throw new Error(`Pas de cdm-java-${version}-sources.jar dans le .m2.`)
  mkdirSync(cache, { recursive: true })
  execSync(`"${JARBIN}" xf "${srcJar}"`, { cwd: cache, maxBuffer: 64 * 1024 * 1024 })
  return cache
}

function walk(dir: string): string[] {
  return (readdirSync(dir, { recursive: true }) as string[])
    .filter((f) => f.endsWith(".java"))
    .map((f) => join(dir, f))
}

export default tool({
  description:
    "Grep le code source réel de CDM (builders/enums/valeurs) pour comprendre comment " +
    "construire un objet correctement. Donne fichier:ligne + extrait. Complète cdm-api.",
  args: {
    pattern: tool.schema.string().describe("Texte ou regex à chercher dans les .java de CDM."),
    max: tool.schema.number().optional().describe("Nb max de résultats (défaut 40)."),
  },
  async execute(args, context) {
    const version = pomCdmVersion(context.directory)
    const root = ensureSources(context.directory, version)
    const re = new RegExp(args.pattern, "i")
    const limit = args.max ?? 40
    const hits: string[] = []
    for (const file of walk(root)) {
      const lines = readFileSync(file, "utf8").split("\n")
      for (let i = 0; i < lines.length; i++) {
        if (re.test(lines[i])) {
          hits.push(`${file.slice(root.length + 1)}:${i + 1}: ${lines[i].trim()}`)
          if (hits.length >= limit) return `${hits.join("\n")}\n\n(limite ${limit} atteinte)`
        }
      }
    }
    return hits.length ? hits.join("\n") : `Aucun match pour /${args.pattern}/ dans cdm-java ${version} sources.`
  },
})
