import groovy.json.JsonOutput
import groovy.xml.XmlSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Reads the XML reports produced by JaCoCo, PMD, Checkstyle, and SpotBugs,
 * aggregates every signal to the class level, calculates a composite risk score,
 * and writes a self-contained HTML report powered by the quality-landscape template.
 *
 * Output: build/reports/quality-landscape/index.html
 */
class CodeQualityRollupTask extends DefaultTask {

    @InputFile
    File templateFile = project.file('site-template/quality-landscape.html.template')

    @OutputFile
    File outputFile = project.file('build/reports/quality-landscape/index.html')

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    @TaskAction
    void generate() {
        def base     = project.file('build/reports')
        def jacocoXml = new File(base, 'jacoco/test/jacocoTestReport.xml')
        def pmdXml    = new File(base, 'pmd/main.xml')
        def csXml     = new File(base, 'checkstyle/main.xml')
        def sbXml     = new File(base, 'spotbugs/main.xml')

        Map<String, Map> classes = [:]

        if (jacocoXml.exists()) parseJacoco(classes, jacocoXml)
        if (pmdXml.exists())    parsePmd(classes, pmdXml)
        if (csXml.exists())     parseCheckstyle(classes, csXml)
        if (sbXml.exists())     parseSpotbugs(classes, sbXml)

        classes.each { _, m -> m.riskScore = riskScore(m) }

        def sorted = classes.values()
                .findAll { it.className && !it.className.endsWith('package-info') }
                .sort { -(it.riskScore as double) }

        def json = JsonOutput.toJson(sorted)

        outputFile.parentFile.mkdirs()
        outputFile.text = templateFile.text.replace('__DATA_JSON__', json)

        logger.lifecycle("Quality landscape → ${outputFile.absolutePath}")
        logger.lifecycle("  Classes analysed : ${sorted.size()}")
        logger.lifecycle("  JaCoCo data      : ${jacocoXml.exists()}")
        logger.lifecycle("  PMD data         : ${pmdXml.exists()}")
        logger.lifecycle("  Checkstyle data  : ${csXml.exists()}")
        logger.lifecycle("  SpotBugs data    : ${sbXml.exists()}")
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    protected Map classEntry(Map<String, Map> map, String key) {
        if (map.containsKey(key)) return map[key]
        def m = [
            className   : key,
            simpleName  : key.split('\\.').last(),
            packageName : key.contains('.') ? key.split('\\.')[0..-2].join('.') : '',
            isInner     : key.contains('$'),
            sourceFile  : '',
            // JaCoCo coverage
            linePct        : 0.0,  linesCovered   : 0, linesMissed    : 0,
            branchPct      : 0.0,  branchesCovered: 0, branchesMissed : 0,
            methodPct      : 0.0,  methodsCovered : 0, methodsMissed  : 0,
            jacocoComplexity: 0,
            // PMD
            pmdTotal      : 0, pmdByCategory : [:],
            maxCyclomatic : 0, maxCognitive  : 0,
            // Checkstyle
            csTotal   : 0, csErrors  : 0, csWarnings: 0,
            // SpotBugs
            sbTotal      : 0, sbByCategory: [:], sbByPriority: [:], sbMinRank: 20,
            // Derived
            riskScore : 0.0
        ]
        map[key] = m
        m
    }

    /** Derives a fully-qualified class name from an absolute file path. */
    protected static String classFromPath(String filePath) {
        def m = filePath.replace('\\', '/') =~ /src\/(?:main|test)\/java\/(.+)\.java$/
        if (!m) return null
        def name = m.group(1).replace('/', '.')
        return name.endsWith('package-info') ? null : name
    }

    /** XmlSlurper configured to ignore external DTDs (needed for JaCoCo). */
    protected static XmlSlurper safeSlurper() {
        def s = new XmlSlurper()
        s.setFeature('http://apache.org/xml/features/disallow-doctype-decl', false)
        s.setFeature('http://apache.org/xml/features/nonvalidating/load-external-dtd', false)
        s
    }

    protected static double pct(int covered, int missed) {
        int total = covered + missed
        total > 0 ? (covered / total * 100.0).round(1) : 0.0
    }

    // -----------------------------------------------------------------------
    // JaCoCo parser
    // -----------------------------------------------------------------------
    // XML shape:
    //   <report>
    //     <package name="org/apache/commons/lang3">
    //       <class name="org/…/StringUtils" sourcefilename="StringUtils.java">
    //         <method …> <counter type="…" missed="n" covered="n"/> … </method>
    //         <counter type="LINE|BRANCH|METHOD|COMPLEXITY|INSTRUCTION" …/>  ← class-level
    //       </class>
    //     </package>
    //   </report>

    protected void parseJacoco(Map<String, Map> classes, File file) {
        def xml = safeSlurper().parse(file)

        xml.package.each { pkg ->
            String pkgName = pkg.@name.toString().replace('/', '.')
            pkg.'class'.each { cls ->
                String className = cls.@name.toString().replace('/', '.')
                def m = classEntry(classes, className)
                m.packageName = pkgName
                if (cls.@sourcefilename.toString()) m.sourceFile = cls.@sourcefilename.toString()

                // cls.counter returns only direct <counter> children (class-level aggregates),
                // NOT the ones nested inside <method> elements.
                cls.counter.each { c ->
                    int missed  = c.@missed.toString().toInteger()
                    int covered = c.@covered.toString().toInteger()
                    switch (c.@type.toString()) {
                        case 'LINE':
                            m.linesCovered = covered; m.linesMissed = missed
                            m.linePct = pct(covered, missed)
                            break
                        case 'BRANCH':
                            m.branchesCovered = covered; m.branchesMissed = missed
                            m.branchPct = pct(covered, missed)
                            break
                        case 'METHOD':
                            m.methodsCovered = covered; m.methodsMissed = missed
                            m.methodPct = pct(covered, missed)
                            break
                        case 'COMPLEXITY':
                            m.jacocoComplexity = covered + missed
                            break
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // PMD parser
    // -----------------------------------------------------------------------
    // XML shape:
    //   <pmd>
    //     <file name="/abs/path/Foo.java">
    //       <violation rule="CyclomaticComplexity" ruleset="Design"
    //                  package="org.…" class="Foo" method="bar" priority="3">
    //         The method 'bar' has a cyclomatic complexity of 12.
    //       </violation>
    //     </file>
    //   </pmd>
    //
    // Note: some violations omit the `class` attribute — fall back to file path.

    protected void parsePmd(Map<String, Map> classes, File file) {
        def xml = safeSlurper().parse(file)

        xml.file.each { f ->
            String filePath  = f.@name.toString()
            String pathClass = classFromPath(filePath)

            f.violation.each { v ->
                String cls = v.@class.toString()
                String pkg = v.@package.toString()

                String fullName
                if (cls) {
                    fullName = pkg ? "${pkg}.${cls}" : cls
                } else if (pathClass) {
                    fullName = pathClass
                } else {
                    return
                }

                def m       = classEntry(classes, fullName)
                String rule = v.@rule.toString()
                String cat  = v.@ruleset.toString()
                String msg  = v.text().trim()

                m.pmdTotal++
                m.pmdByCategory[cat] = ((m.pmdByCategory[cat] ?: 0) as int) + 1

                // Extract complexity values from message text
                if (rule == 'CyclomaticComplexity') {
                    def match = msg =~ /cyclomatic complexity of (\d+)/
                    if (match) {
                        int val = match.group(1).toInteger()
                        m.maxCyclomatic = Math.max(m.maxCyclomatic as int, val)
                    }
                } else if (rule == 'CognitiveComplexity') {
                    def match = msg =~ /cognitive complexity of (\d+)/
                    if (match) {
                        int val = match.group(1).toInteger()
                        m.maxCognitive = Math.max(m.maxCognitive as int, val)
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Checkstyle parser
    // -----------------------------------------------------------------------
    // XML shape:
    //   <checkstyle>
    //     <file name="/abs/path/Foo.java">
    //       <error line="n" severity="error|warning|info" message="…" source="…"/>
    //     </file>
    //   </checkstyle>

    protected void parseCheckstyle(Map<String, Map> classes, File file) {
        def xml = safeSlurper().parse(file)

        xml.file.each { f ->
            String filePath = f.@name.toString()
            String className = classFromPath(filePath)
            if (!className) return
            if (f.error.size() == 0) return

            def m = classEntry(classes, className)
            f.error.each { e ->
                m.csTotal++
                switch (e.@severity.toString()) {
                    case 'error'  : m.csErrors++;   break
                    case 'warning': m.csWarnings++; break
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // SpotBugs parser
    // -----------------------------------------------------------------------
    // XML shape:
    //   <BugCollection>
    //     <BugInstance type="…" priority="1-3" rank="1-20" category="CORRECTNESS|…">
    //       <Class classname="org.…Foo" primary="true">…</Class>
    //       …
    //     </BugInstance>
    //   </BugCollection>

    protected void parseSpotbugs(Map<String, Map> classes, File file) {
        def xml = safeSlurper().parse(file)

        xml.BugInstance.each { bug ->
            String category = bug.@category.toString()
            int    priority = bug.@priority.toString().toInteger()
            int    rank     = bug.@rank.toString().toInteger()

            // Find primary class element
            def primaryCls = bug.Class.find { it.@primary.toString() == 'true' }
            if (!primaryCls || primaryCls.size() == 0) primaryCls = bug.Class[0]
            if (!primaryCls || primaryCls.size() == 0) return

            String className = primaryCls.@classname.toString()
            if (!className) return

            def m = classEntry(classes, className)
            m.sbTotal++
            m.sbByCategory[category]          = ((m.sbByCategory[category]                 ?: 0) as int) + 1
            m.sbByPriority[priority.toString()] = ((m.sbByPriority[priority.toString()] ?: 0) as int) + 1
            m.sbMinRank = Math.min(m.sbMinRank as int, rank)
        }
    }

    // -----------------------------------------------------------------------
    // Risk score
    // -----------------------------------------------------------------------
    // A composite signal that rewards coverage and penalises complexity,
    // violations, and SpotBugs bugs (weighted most heavily).
    //
    //   riskScore = (1 - linePct/100) * jacocoComplexity
    //             + sbTotal * 10
    //             + pmdTotal * 0.5
    //
    // A class that is fully covered scores 0 for the first term but still
    // shows up if SpotBugs or PMD found real issues.

    protected static double riskScore(Map m) {
        double uncov      = (100.0 - (m.linePct as double)) / 100.0
        int    complexity = Math.max(m.jacocoComplexity as int, m.maxCyclomatic as int)
        if (complexity == 0) complexity = 1

        double score = uncov * complexity + (m.sbTotal as int) * 10.0 + (m.pmdTotal as int) * 0.5
        return score.round(2)
    }
}
