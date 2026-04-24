import groovy.json.JsonOutput
import groovy.xml.XmlSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Reads XML reports from JaCoCo, PMD, Checkstyle, and SpotBugs, aggregates
 * signals to the class level, and writes:
 *
 *  build/reports/quality-landscape/index.html   — scatter / table dashboard
 *  build/reports/quality-landscape/sources/     — one JSON per class with
 *      embedded source lines, per-line coverage status, and per-line
 *      annotations from all four tools (for the code browser).
 */
class CodeQualityRollupTask extends DefaultTask {

    @InputFile
    File templateFile = project.file('site-template/quality-landscape.html.template')

    @OutputFile
    File outputFile = project.file('build/reports/quality-landscape/index.html')

    // ── entry point ────────────────────────────────────────────────────────

    @TaskAction
    void generate() {
        def base      = project.file('build/reports')
        def jacocoXml = new File(base, 'jacoco/test/jacocoTestReport.xml')
        def pmdXml    = new File(base, 'pmd/main.xml')
        def csXml     = new File(base, 'checkstyle/main.xml')
        def sbXml     = new File(base, 'spotbugs/main.xml')
        def srcRoot   = project.file('vendor/commons-lang/src/main/java')

        // class-level rollup:  className -> metric map
        Map<String, Map> classes = [:]
        // line-level coverage: relPath (org/…/Foo.java) -> lineNum -> 'C'|'M'|'P'
        Map<String, Map<Integer, String>> lineCov = [:]
        // line-level annotations: outerClassName -> lineNum -> [ {t,r,m,…} ]
        Map<String, Map<Integer, List<Map>>> lineAnns = [:]

        if (jacocoXml.exists()) parseJacoco(classes, lineCov, jacocoXml)
        if (pmdXml.exists())    parsePmd(classes, lineAnns, pmdXml)
        if (csXml.exists())     parseCheckstyle(classes, lineAnns, csXml)
        if (sbXml.exists())     parseSpotbugs(classes, lineAnns, sbXml)

        classes.each { _, m -> m.riskScore = riskScore(m) }

        def sorted = classes.values()
                .findAll { it.className && !it.className.endsWith('package-info') }
                .sort { -(it.riskScore as double) }

        // Dashboard HTML
        def json = JsonOutput.toJson(sorted)
        outputFile.parentFile.mkdirs()
        outputFile.text = templateFile.text.replace('__DATA_JSON__', json)

        // Per-class source JSONs
        File sourcesDir = new File(outputFile.parentFile, 'sources')
        sourcesDir.mkdirs()
        generateSourceJsons(sorted, lineCov, lineAnns, srcRoot, sourcesDir)

        logger.lifecycle("Quality landscape → ${outputFile.absolutePath}")
        logger.lifecycle("  Classes analysed : ${sorted.size()}")
        logger.lifecycle("  Source JSONs     : ${sourcesDir.listFiles()?.size() ?: 0}")
    }

    // ── helpers ────────────────────────────────────────────────────────────

    protected Map classEntry(Map<String, Map> map, String key) {
        if (map.containsKey(key)) return map[key]
        def m = [
            className: key, simpleName: key.split('\\.').last(),
            packageName: key.contains('.') ? key.split('\\.')[0..-2].join('.') : '',
            isInner: key.contains('$'), sourceFile: '',
            linePct: 0.0, linesCovered: 0, linesMissed: 0,
            branchPct: 0.0, branchesCovered: 0, branchesMissed: 0,
            methodPct: 0.0, methodsCovered: 0, methodsMissed: 0,
            jacocoComplexity: 0,
            pmdTotal: 0, pmdByCategory: [:], maxCyclomatic: 0, maxCognitive: 0,
            csTotal: 0, csErrors: 0, csWarnings: 0,
            sbTotal: 0, sbByCategory: [:], sbByPriority: [:], sbMinRank: 20,
            riskScore: 0.0
        ]
        map[key] = m; m
    }

    /** Strips the inner-class suffix: Foo$Bar → Foo */
    protected static String outerClass(String className) {
        className.contains('$') ? className.split('\\$')[0] : className
    }

    /** org/apache/commons/lang3/StringUtils.java from an outer class FQN */
    protected static String relPath(String outerFqn) {
        outerFqn.replace('.', '/') + '.java'
    }

    /** FQN from absolute file path; null for package-info */
    protected static String classFromPath(String p) {
        def m = p.replace('\\', '/') =~ /src\/(?:main|test)\/java\/(.+)\.java$/
        if (!m) return null
        def n = m.group(1).replace('/', '.')
        n.endsWith('package-info') ? null : n
    }

    protected static XmlSlurper safeSlurper() {
        def s = new XmlSlurper()
        s.setFeature('http://apache.org/xml/features/disallow-doctype-decl', false)
        s.setFeature('http://apache.org/xml/features/nonvalidating/load-external-dtd', false)
        s
    }

    protected static double pct(int covered, int missed) {
        int t = covered + missed; t > 0 ? (covered / t * 100.0).round(1) : 0.0
    }

    // ── JaCoCo ────────────────────────────────────────────────────────────
    // <package name="org/…"><class …><counter …/></class>
    //                        <sourcefile name="Foo.java"><line nr="n" mi="0" ci="3" mb="1" cb="1"/></sourcefile>

    protected void parseJacoco(Map<String, Map> classes,
                               Map<String, Map<Integer, String>> lineCov, File file) {
        def xml = safeSlurper().parse(file)
        xml.package.each { pkg ->
            String pkgName = pkg.@name.toString().replace('/', '.')
            // class-level aggregates
            pkg.'class'.each { cls ->
                String cn = cls.@name.toString().replace('/', '.')
                def m = classEntry(classes, cn)
                m.packageName = pkgName
                if (cls.@sourcefilename.toString()) m.sourceFile = cls.@sourcefilename.toString()
                cls.counter.each { c ->
                    int mi = c.@missed.toString().toInteger()
                    int ci = c.@covered.toString().toInteger()
                    switch (c.@type.toString()) {
                        case 'LINE':       m.linesCovered=ci; m.linesMissed=mi; m.linePct=pct(ci,mi); break
                        case 'BRANCH':     m.branchesCovered=ci; m.branchesMissed=mi; m.branchPct=pct(ci,mi); break
                        case 'METHOD':     m.methodsCovered=ci; m.methodsMissed=mi; m.methodPct=pct(ci,mi); break
                        case 'COMPLEXITY': m.jacocoComplexity=ci+mi; break
                    }
                }
            }
            // line-level coverage from <sourcefile>
            pkg.sourcefile.each { sf ->
                String rel = pkg.@name.toString() + '/' + sf.@name.toString()
                def lines = [:]
                sf.line.each { l ->
                    int nr = l.@nr.toString().toInteger()
                    int mi = l.@mi.toString().toInteger()
                    int ci = l.@ci.toString().toInteger()
                    int mb = l.@mb.toString().toInteger()
                    if (mi == 0 && ci == 0) return
                    lines[nr] = (ci > 0 && (mi > 0 || mb > 0)) ? 'P' : ci > 0 ? 'C' : 'M'
                }
                if (lines) lineCov[rel] = lines
            }
        }
    }

    // ── PMD ───────────────────────────────────────────────────────────────

    protected void parsePmd(Map<String, Map> classes,
                            Map<String, Map<Integer, List<Map>>> lineAnns, File file) {
        def xml = safeSlurper().parse(file)
        xml.file.each { f ->
            String fp       = f.@name.toString()
            String pathCls  = classFromPath(fp)
            f.violation.each { v ->
                String cls  = v.@class.toString()
                String pkg  = v.@package.toString()
                String full = cls ? (pkg ? "${pkg}.${cls}" : cls) : (pathCls ?: null)
                if (!full) return

                def m    = classEntry(classes, full)
                String rule = v.@rule.toString()
                String cat  = v.@ruleset.toString()
                String msg  = v.text().trim()
                int    sev  = v.@priority.toString().toInteger()

                m.pmdTotal++
                m.pmdByCategory[cat] = ((m.pmdByCategory[cat] ?: 0) as int) + 1

                if (rule == 'CyclomaticComplexity') {
                    def match = msg =~ /cyclomatic complexity of (\d+)/
                    if (match) m.maxCyclomatic = Math.max(m.maxCyclomatic as int, match.group(1).toInteger())
                } else if (rule == 'CognitiveComplexity') {
                    def match = msg =~ /cognitive complexity of (\d+)/
                    if (match) m.maxCognitive = Math.max(m.maxCognitive as int, match.group(1).toInteger())
                }

                // line annotation
                int line = v.@beginline.toString().toInteger()
                addLineAnn(lineAnns, outerClass(full), line,
                    [t: 'pmd', r: rule, c: cat, m: msg.take(200), s: sev])
            }
        }
    }

    // ── Checkstyle ────────────────────────────────────────────────────────

    protected void parseCheckstyle(Map<String, Map> classes,
                                   Map<String, Map<Integer, List<Map>>> lineAnns, File file) {
        def xml = safeSlurper().parse(file)
        xml.file.each { f ->
            String fp  = f.@name.toString()
            String cn  = classFromPath(fp)
            if (!cn || f.error.size() == 0) return
            def m = classEntry(classes, cn)
            f.error.each { e ->
                m.csTotal++
                String sev = e.@severity.toString()
                if (sev == 'error') m.csErrors++ else m.csWarnings++

                int line = e.@line.toString().toInteger()
                String src = e.@source.toString().split('\\.').last()
                addLineAnn(lineAnns, outerClass(cn), line,
                    [t: 'cs', r: src, m: e.@message.toString().take(200), s: sev])
            }
        }
    }

    // ── SpotBugs ──────────────────────────────────────────────────────────

    protected void parseSpotbugs(Map<String, Map> classes,
                                 Map<String, Map<Integer, List<Map>>> lineAnns, File file) {
        def xml = safeSlurper().parse(file)
        xml.BugInstance.each { bug ->
            String cat  = bug.@category.toString()
            int    pri  = bug.@priority.toString().toInteger()
            int    rank = bug.@rank.toString().toInteger()
            String type = bug.@type.toString()

            def pc = bug.Class.find { it.@primary.toString() == 'true' }
            if (!pc || pc.size() == 0) pc = bug.Class[0]
            if (!pc || pc.size() == 0) return
            String cn = pc.@classname.toString()
            if (!cn) return

            def m = classEntry(classes, cn)
            m.sbTotal++
            m.sbByCategory[cat]            = ((m.sbByCategory[cat]                     ?: 0) as int) + 1
            m.sbByPriority[pri.toString()] = ((m.sbByPriority[pri.toString()]           ?: 0) as int) + 1
            m.sbMinRank = Math.min(m.sbMinRank as int, rank)

            // primary source line
            def sl = bug.SourceLine.find { it.@primary.toString() == 'true' }
            if (!sl || sl.size() == 0) sl = bug.SourceLine[0]
            if (sl && sl.size() > 0) {
                String startStr = sl.@start.toString()
                if (startStr) {
                    int line = startStr.toInteger()
                    String msg = bug.ShortMessage.text() ?: type
                    addLineAnn(lineAnns, outerClass(cn), line,
                        [t: 'sb', r: type, c: cat, m: msg.take(200), p: pri, rank: rank])
                }
            }
        }
    }

    // ── line annotation helper ─────────────────────────────────────────────

    protected static void addLineAnn(Map<String, Map<Integer, List<Map>>> lineAnns,
                                     String cls, int line, Map ann) {
        if (!lineAnns.containsKey(cls)) lineAnns[cls] = [:]
        if (!lineAnns[cls].containsKey(line)) lineAnns[cls][line] = []
        lineAnns[cls][line] << ann
    }

    // ── source JSON generation ─────────────────────────────────────────────

    protected void generateSourceJsons(List sorted,
                                       Map<String, Map<Integer, String>> lineCov,
                                       Map<String, Map<Integer, List<Map>>> lineAnns,
                                       File srcRoot, File outDir) {
        Set<String> written = []
        sorted.each { m ->
            String cn    = m.className as String
            String outer = outerClass(cn)
            if (written.contains(outer)) return   // already generated for this source file
            written << outer

            File src = new File(srcRoot, relPath(outer))
            if (!src.exists()) return

            List<String> lines = src.readLines('UTF-8')
            String rp  = relPath(outer)
            def   cov  = lineCov[rp]       ?: [:]
            def   anns = lineAnns[outer]   ?: [:]

            // Convert integer keys to string keys for JSON
            def covStr  = cov.collectEntries  { k, v -> [k.toString(), v] }
            def annsStr = anns.collectEntries { k, v -> [k.toString(), v] }

            def payload = [
                n  : outer,
                f  : src.name,
                src: lines,
                cov: covStr,
                ann: annsStr
            ]

            File out = new File(outDir, "${outer}.json")
            out.text = JsonOutput.toJson(payload)
        }
    }

    // ── risk score ────────────────────────────────────────────────────────

    protected static double riskScore(Map m) {
        double uncov = (100.0 - (m.linePct as double)) / 100.0
        int    cpx   = Math.max(m.jacocoComplexity as int, m.maxCyclomatic as int)
        if (cpx == 0) cpx = 1
        double score = uncov * cpx + (m.sbTotal as int) * 10.0 + (m.pmdTotal as int) * 0.5
        return score.round(2)
    }
}
