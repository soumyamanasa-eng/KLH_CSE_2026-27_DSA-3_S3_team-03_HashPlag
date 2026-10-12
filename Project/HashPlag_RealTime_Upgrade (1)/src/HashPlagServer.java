import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;
import java.util.zip.*;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/**
 * HashPlag Detect - BACKEND (Java)
 * Polynomial Rolling Hash + Rabin-Karp plagiarism detection, served over HTTP.
 * DSA-3 (25CS2103E) | Team 03 | Section 03
 *
 *   GET  /              -> frontend (web/index.html, style.css, app.js)
 *   POST /api/compare   -> receives uploaded documents (txt, docx, pdf, pptx, xlsx, ...), returns JSON results
 *
 * Uses the JDK HTTP server plus the bundled PDFBox library for PDF export.
 */
public class HashPlagServer {

    // ---------- Polynomial rolling hash parameters ----------
    private static final long BASE = 256;
    private static final long MOD  = 1_000_000_007L;

    static class Doc {
        final String name, text, sourceText;
        final WritingAnalysis writingAnalysis;

        Doc(String name, String text, String sourceText) {
            this.name = name;
            this.text = text;
            this.sourceText = sourceText;
            this.writingAnalysis = analyzeWritingStyle(name, sourceText);
        }
    }

    /**
     * A lightweight, experimental writing-style heuristic.
     * This is NOT a trained AI detector and cannot establish authorship.
     */
    static class WritingAnalysis {
        final int score; // -1 means not enough suitable text to assess
        final String label;
        final String note;
        final List<String> signals;

        WritingAnalysis(int score, String label, String note, List<String> signals) {
            this.score = score;
            this.label = label;
            this.note = note;
            this.signals = signals;
        }
    }

    static class MatchSpan {
        final int aStart, bStart, length;
        MatchSpan(int aStart, int bStart, int length) {
            this.aStart = aStart;
            this.bStart = bStart;
            this.length = length;
        }
    }

    static class LinePairMatch {
        final int aLine, bLine;
        final List<MatchSpan> spans = new ArrayList<>();
        LinePairMatch(int aLine, int bLine) {
            this.aLine = aLine;
            this.bLine = bLine;
        }
    }

    static class Result {
        final Doc a, b;
        final boolean[] markA, markB;
        final int hits, collisions, verified, shortMatches;
        final LinkedHashMap<String, LinePairMatch> matches;
        final double simA, simB, overall, wordNgramOverlap;
        Result(Doc a, Doc b, boolean[] mA, boolean[] mB, int hits, int coll,
               int ver, int shortMatches, LinkedHashMap<String, LinePairMatch> matches) {
            this.a = a;
            this.b = b;
            markA = mA;
            markB = mB;
            this.hits = hits;
            this.collisions = coll;
            this.verified = ver;
            this.shortMatches = shortMatches;
            this.matches = matches;
            simA = percent(mA);
            simB = percent(mB);
            overall = (simA + simB) / 2.0;
            wordNgramOverlap = wordNgramJaccard(a.text, b.text);
        }
        static double percent(boolean[] m) {
            if (m.length == 0) return 0;
            int c = 0;
            for (boolean x : m) if (x) c++;
            return 100.0 * c / m.length;
        }
    }

    // ================= Preprocessing =================
    static String preprocess(String raw) {
        // Preserve line breaks so every match can be reported against a line number.
        String s = raw.replace("\r\n", "\n").replace('\r', '\n').toLowerCase(Locale.ROOT);
        String[] lines = s.split("\\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].replaceAll("[^a-z0-9\\s]", " ");
            line = line.replaceAll("[ \\t\\f]+", " ").trim();
            if (i > 0) out.append('\n');
            out.append(line);
        }
        return out.toString();
    }

    // ================= Optional programming-assignment normalization =================
    // This is a token-based structural comparison mode, not a compiler or AST matcher.
    static String preprocessCode(String raw, String fileName) {
        String s = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        // Remove block comments while preserving their line breaks for line reporting.
        Matcher block = Pattern.compile("(?s)/\\*.*?\\*/").matcher(s);
        StringBuffer cleaned = new StringBuffer();
        while (block.find()) {
            String replacement = block.group().replaceAll("[^\\n]", " ");
            block.appendReplacement(cleaned, Matcher.quoteReplacement(replacement));
        }
        block.appendTail(cleaned);
        s = cleaned.toString();

        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        StringBuilder out = new StringBuilder();
        String[] lines = s.split("\\n", -1);
        Set<String> keywords = new HashSet<>(Arrays.asList(("abstract assert boolean break byte case catch char class const continue default " +
            "do double else enum extends final finally float for if implements import instanceof int interface long native new package " +
            "private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while " +
            "true false null var let function async await export from as def lambda pass raise with yield global nonlocal and or not is in " +
            "None True False self print include define ifdef ifndef endif pragma using namespace std auto register signed sizeof struct typedef union unsigned " +
            "goto volatile extern inline restrict _Bool _Complex _Imaginary template typename public private protected virtual override constexpr nullptr " +
            "import package func go defer select chan map range fallthrough interface").split("\\s+")));
        Pattern tokenPattern = Pattern.compile("\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'(?:\\\\.|[^'\\\\])*'|[A-Za-z_$][A-Za-z0-9_$]*|\\d+(?:\\.\\d+)?|==|!=|<=|>=|\\+\\+|--|&&|\\|\\||->|::|\\S");
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            // Strip common end-of-line comments. Keep C/C++ preprocessor directives.
            if (Arrays.asList("java","c","cpp","js","css","tex").contains(ext)) {
                line = line.replaceFirst("//.*$", "");
            } else if (ext.equals("py")) {
                line = line.replaceFirst("#.*$", "");
            }
            Matcher tok = tokenPattern.matcher(line);
            StringBuilder normalized = new StringBuilder();
            while (tok.find()) {
                String t = tok.group();
                String low = t.toLowerCase(Locale.ROOT);
                if ((t.startsWith("\\\"") && t.endsWith("\\\"")) || (t.startsWith("'") && t.endsWith("'"))) t = "STRING_LITERAL";
                else if (t.matches("\\d+(?:\\.\\d+)?")) t = "NUMBER_LITERAL";
                else if (t.matches("[A-Za-z_$][A-Za-z0-9_$]*") && !keywords.contains(low)) t = "IDENTIFIER";
                if (normalized.length() > 0) normalized.append(' ');
                normalized.append(t.toLowerCase(Locale.ROOT));
            }
            if (li > 0) out.append('\n');
            out.append(normalized.toString().trim());
        }
        return out.toString();
    }

    // Supplemental lexical overlap metric. It compares word n-grams; it does not
    // understand meaning and should not be presented as semantic/paraphrase detection.
    static double wordNgramJaccard(String a, String b) {
        List<String> wa = wordTokens(a);
        List<String> wb = wordTokens(b);
        int n = Math.min(3, Math.min(wa.size(), wb.size()));
        if (n <= 0) return 0.0;
        Set<String> sa = ngrams(wa, n), sb = ngrams(wb, n);
        if (sa.isEmpty() && sb.isEmpty()) return 0.0;
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        Set<String> intersection = new HashSet<>(sa);
        intersection.retainAll(sb);
        return union.isEmpty() ? 0.0 : 100.0 * intersection.size() / union.size();
    }

    static Set<String> ngrams(List<String> words, int n) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i + n <= words.size(); i++) out.add(String.join(" ", words.subList(i, i + n)));
        return out;
    }

    // ================= Experimental AI-like writing-style indicator =================
    // These rules identify stylistic patterns only. They must never be interpreted
    // as a reliable determination that a document was written by AI or a human.
    static List<String> wordTokens(String input) {
        List<String> words = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?U)\\b[\\p{L}]+(?:['’][\\p{L}]+)?\\b")
                                 .matcher(input.toLowerCase(Locale.ROOT));
        while (matcher.find()) words.add(matcher.group());
        return words;
    }

    static WritingAnalysis notAssessed(String label, String note) {
        return new WritingAnalysis(-1, label, note, new ArrayList<>());
    }

    static WritingAnalysis analyzeWritingStyle(String fileName, String rawText) {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (lowerName.matches(".*\\.(java|py|c|cpp|js|css|tex|json|xml|csv|tsv|log|html|htm)$")) {
            return notAssessed("Not assessed", "This file type is usually code or structured data; the writing-style heuristic is intended for prose.");
        }

        String clean = rawText == null ? "" : rawText.replace('\r', '\n').replaceAll("[\\t\\f ]+", " ").trim();
        List<String> words = wordTokens(clean);
        String[] sentences = clean.isEmpty()
            ? new String[0]
            : clean.split("(?<=[.!?])\\s+|\\n+");

        List<Integer> sentenceLengths = new ArrayList<>();
        List<String> sentenceStarts = new ArrayList<>();
        for (String sentence : sentences) {
            List<String> sentenceWords = wordTokens(sentence);
            if (sentenceWords.size() >= 4) {
                sentenceLengths.add(sentenceWords.size());
                sentenceStarts.add(sentenceWords.get(0) + " " + sentenceWords.get(1));
            }
        }

        if (words.size() < 80 || sentenceLengths.size() < 4) {
            return notAssessed("Insufficient text", "At least 80 words and 4 sentences are needed. Short text cannot be assessed reliably.");
        }

        int score = 0;
        List<String> signals = new ArrayList<>();

        // Signal 1: unusually consistent sentence lengths.
        double mean = 0.0;
        for (int length : sentenceLengths) mean += length;
        mean /= sentenceLengths.size();
        double variance = 0.0;
        for (int length : sentenceLengths) variance += Math.pow(length - mean, 2);
        variance /= sentenceLengths.size();
        double coefficientOfVariation = mean == 0.0 ? 0.0 : Math.sqrt(variance) / mean;
        if (coefficientOfVariation < 0.25) {
            score += 25;
            signals.add("Sentence lengths are very consistent.");
        } else if (coefficientOfVariation < 0.45) {
            score += 15;
            signals.add("Sentence lengths show some consistency.");
        } else if (coefficientOfVariation < 0.65) {
            score += 5;
        } else {
            signals.add("Sentence lengths vary noticeably.");
        }

        // Signal 2: repeated two-word sentence openings.
        Set<String> distinctStarts = new HashSet<>(sentenceStarts);
        double repeatedStartRate = (sentenceStarts.size() - distinctStarts.size()) / (double) sentenceStarts.size();
        if (repeatedStartRate >= 0.35) {
            score += 25;
            signals.add("Several sentences begin with the same two-word pattern.");
        } else if (repeatedStartRate >= 0.20) {
            score += 15;
            signals.add("Some sentence openings repeat.");
        } else if (repeatedStartRate >= 0.10) {
            score += 5;
        } else {
            signals.add("Sentence openings are mostly varied.");
        }

        // Signal 3: presence of formulaic transition phrases.
        String[] formulaicPhrases = {
            "in conclusion", "it is important to note", "it is worth noting",
            "furthermore", "moreover", "in today's world", "plays a crucial role",
            "on the other hand", "in summary", "to sum up", "in addition",
            "first and foremost", "delve into", "overall", "it should be noted"
        };
        int phraseCount = 0;
        String lowerText = clean.toLowerCase(Locale.ROOT);
        for (String phrase : formulaicPhrases) {
            Matcher pm = Pattern.compile("\\b" + Pattern.quote(phrase) + "\\b").matcher(lowerText);
            while (pm.find()) phraseCount++;
        }
        double phrasesPerThousand = phraseCount * 1000.0 / words.size();
        if (phrasesPerThousand >= 4.0) {
            score += 25;
            signals.add("Formulaic transition phrases appear frequently.");
        } else if (phrasesPerThousand >= 2.0) {
            score += 15;
            signals.add("Some formulaic transition phrases appear.");
        } else if (phraseCount > 0) {
            score += 5;
            signals.add("A formulaic transition phrase appears.");
        } else {
            signals.add("Few phrases from the heuristic's fixed list were found.");
        }

        // Signal 4: vocabulary repetition. This is highly content-dependent.
        double vocabularyRatio = new HashSet<>(words).size() / (double) words.size();
        if (vocabularyRatio < 0.35) {
            score += 25;
            signals.add("Vocabulary repetition is high.");
        } else if (vocabularyRatio < 0.45) {
            score += 15;
            signals.add("Vocabulary repetition is moderate to high.");
        } else if (vocabularyRatio < 0.55) {
            score += 5;
        } else {
            signals.add("Vocabulary diversity is relatively high.");
        }

        score = Math.max(0, Math.min(100, score));
        String label;
        if (score >= 60) label = "More AI-like style signals";
        else if (score >= 30) label = "Mixed / inconclusive";
        else label = "Fewer AI-like style signals";

        String note = "Experimental rule-based style score, not an AI probability or proof of authorship. Human writing may score high and AI writing may score low.";
        return new WritingAnalysis(score, label, note, signals);
    }

    // ================= Polynomial rolling hash =================
    static long initialHash(String s, int w) {
        long h = 0;
        for (int i = 0; i < w; i++) h = (h * BASE + s.charAt(i)) % MOD;
        return h;
    }
    static long highPower(int w) {
        long p = 1;
        for (int i = 1; i < w; i++) p = (p * BASE) % MOD;
        return p;
    }
    static long roll(long h, char out, char in, long pow) {
        h = (h - out * pow % MOD + MOD) % MOD;
        return (h * BASE + in) % MOD;
    }

    // A line's offsets refer to positions in the complete preprocessed document.
    static class TextLine {
        final int number, start, end;
        final String value;
        TextLine(int number, int start, int end, String value) {
            this.number = number;
            this.start = start;
            this.end = end;
            this.value = value;
        }
        int length() { return end - start; }
    }

    static List<TextLine> getLines(String text) {
        List<TextLine> lines = new ArrayList<>();
        int lineNumber = 1;
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                lines.add(new TextLine(lineNumber++, start, i, text.substring(start, i)));
                start = i + 1;
            }
        }
        return lines;
    }

    static class WindowRef {
        final int absoluteStart, lineNumber;
        WindowRef(int absoluteStart, int lineNumber) {
            this.absoluteStart = absoluteStart;
            this.lineNumber = lineNumber;
        }
    }

    static String spanKey(int aStart, int bStart, int length) {
        return aStart + ":" + bStart + ":" + length;
    }

    static boolean recordMatch(LinkedHashMap<String, LinePairMatch> matches,
                               Set<String> seenSpans,
                               boolean[] markA, boolean[] markB,
                               int aStart, int bStart, int length,
                               int aLine, int bLine) {
        for (int k = 0; k < length; k++) {
            markA[aStart + k] = true;
            markB[bStart + k] = true;
        }
        String uniqueSpan = spanKey(aStart, bStart, length);
        if (!seenSpans.add(uniqueSpan)) return false;
        String key = aLine + ":" + bLine;
        LinePairMatch pair = matches.computeIfAbsent(key, ignored -> new LinePairMatch(aLine, bLine));
        pair.spans.add(new MatchSpan(aStart, bStart, length));
        return true;
    }

    // Lines shorter than the selected window are checked as complete phrases, but
    // phrases shorter than 10 characters are ignored to avoid noisy results such as "s".
    static int findShortLineMatches(List<TextLine> linesA, List<TextLine> linesB,
                                    boolean patternFromA,
                                    LinkedHashMap<String, LinePairMatch> matches,
                                    Set<String> seenSpans,
                                    boolean[] markA, boolean[] markB, int windowSize) {
        int count = 0;
        List<TextLine> patterns = patternFromA ? linesA : linesB;
        List<TextLine> targets  = patternFromA ? linesB : linesA;
        for (TextLine pattern : patterns) {
            int len = pattern.length();
            if (len < 10 || len >= windowSize) continue;
            if (pattern.value.trim().isEmpty()) continue;
            for (TextLine target : targets) {
                int from = 0;
                while (from <= target.value.length() - len) {
                    int found = target.value.indexOf(pattern.value, from);
                    if (found < 0) break;
                    int aStart = patternFromA ? pattern.start : target.start + found;
                    int bStart = patternFromA ? target.start + found : pattern.start;
                    int aLine = patternFromA ? pattern.number : target.number;
                    int bLine = patternFromA ? target.number : pattern.number;
                    if (recordMatch(matches, seenSpans, markA, markB,
                                    aStart, bStart, len, aLine, bLine)) count++;
                    from = found + 1;
                }
            }
        }
        return count;
    }

    // ================= Line-aware Rabin-Karp comparison =================
    // Hash windows are built and searched within individual lines only. A window
    // can never cross a newline, so matches are associated with the correct lines.
    static Result compare(Doc a, Doc b, int w) {
        boolean[] markA = new boolean[a.text.length()];
        boolean[] markB = new boolean[b.text.length()];
        int hits = 0, coll = 0, ver = 0;
        LinkedHashMap<String, LinePairMatch> matches = new LinkedHashMap<>();
        Set<String> seenSpans = new HashSet<>();
        List<TextLine> linesA = getLines(a.text);
        List<TextLine> linesB = getLines(b.text);
        if (a.text.isEmpty() || b.text.isEmpty())
            return new Result(a, b, markA, markB, 0, 0, 0, 0, matches);

        long pow = highPower(w);
        Map<Long, List<WindowRef>> table = new HashMap<>();

        // Index only complete, w-character windows that stay on one line.
        for (TextLine line : linesA) {
            if (line.length() < w) continue;
            long h = initialHash(line.value, w);
            for (int offset = 0; ; offset++) {
                table.computeIfAbsent(h, ignored -> new ArrayList<>())
                     .add(new WindowRef(line.start + offset, line.number));
                if (offset + w >= line.length()) break;
                h = roll(h, line.value.charAt(offset), line.value.charAt(offset + w), pow);
            }
        }

        // Search B's line windows in the A index and verify every hash hit.
        for (TextLine line : linesB) {
            if (line.length() < w) continue;
            long h = initialHash(line.value, w);
            for (int offset = 0; ; offset++) {
                List<WindowRef> candidates = table.get(h);
                if (candidates != null) {
                    int bStart = line.start + offset;
                    for (WindowRef candidate : candidates) {
                        hits++;
                        if (a.text.regionMatches(candidate.absoluteStart, b.text, bStart, w)) {
                            ver++;
                            recordMatch(matches, seenSpans, markA, markB,
                                        candidate.absoluteStart, bStart, w,
                                        candidate.lineNumber, line.number);
                        } else {
                            coll++;
                        }
                    }
                }
                if (offset + w >= line.length()) break;
                h = roll(h, line.value.charAt(offset), line.value.charAt(offset + w), pow);
            }
        }

        // If a line is shorter than the selected window, compare its complete
        // phrase (minimum 10 characters) against other lines. This preserves useful
        // short-line matches without ever reporting a single character as a match.
        int shortMatches = 0;
        shortMatches += findShortLineMatches(linesA, linesB, true, matches, seenSpans, markA, markB, w);
        shortMatches += findShortLineMatches(linesA, linesB, false, matches, seenSpans, markA, markB, w);

        return new Result(a, b, markA, markB, hits, coll, ver, shortMatches, matches);
    }

    static String verdict(double p) {
        if (p >= 70) return "HIGH plagiarism";
        if (p >= 30) return "MODERATE similarity";
        if (p > 0)   return "LOW similarity";
        return "No similarity";
    }

    // ================= Highlighting / JSON helpers =================
    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
    static String highlight(String text, boolean[] mark) {
        StringBuilder sb = new StringBuilder();
        boolean open = false;
        for (int i = 0; i < text.length(); i++) {
            if (mark[i] && !open) { sb.append("<mark>"); open = true; }
            if (!mark[i] && open) { sb.append("</mark>"); open = false; }
            sb.append(esc(String.valueOf(text.charAt(i))));
        }
        if (open) sb.append("</mark>");
        return sb.toString();
    }
    // Collect only spans belonging to this particular line pair. Adjacent/overlapping
    // windows are merged into phrases rather than displayed as many tiny fragments.
    static String matchedTextFromSpans(String text, List<MatchSpan> spans, boolean sideA) {
        List<int[]> intervals = new ArrayList<>();
        for (MatchSpan span : spans) {
            int start = sideA ? span.aStart : span.bStart;
            intervals.add(new int[]{start, start + span.length});
        }
        intervals.sort(Comparator.comparingInt(x -> x[0]));
        StringBuilder out = new StringBuilder();
        int currentStart = -1, currentEnd = -1;
        for (int[] interval : intervals) {
            if (currentStart < 0) {
                currentStart = interval[0];
                currentEnd = interval[1];
            } else if (interval[0] <= currentEnd) {
                currentEnd = Math.max(currentEnd, interval[1]);
            } else {
                appendSnippet(out, text, currentStart, currentEnd);
                currentStart = interval[0];
                currentEnd = interval[1];
            }
        }
        if (currentStart >= 0) appendSnippet(out, text, currentStart, currentEnd);
        return out.toString();
    }

    static void appendSnippet(StringBuilder out, String text, int start, int end) {
        String snippet = text.substring(start, end).trim();
        if (snippet.isEmpty()) return;
        if (out.length() > 0) out.append(" ... ");
        out.append(snippet);
    }

    static String q(String s) {   // JSON string literal
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    static String toJson(List<Doc> docs, List<Result> results, int w, List<String> warnings, String mode) {
        StringBuilder j = new StringBuilder("{\"window\":" + w + ",\"mode\":" + q(mode) + ",\"warnings\":[");
        for (int i = 0; i < warnings.size(); i++) { if (i > 0) j.append(','); j.append(q(warnings.get(i))); }
        j.append("],\"documents\":[");
        for (int i = 0; i < docs.size(); i++) {
            if (i > 0) j.append(',');
            j.append(q(docs.get(i).name));
        }
        j.append("],\"writingAnalysis\":[");
        for (int i = 0; i < docs.size(); i++) {
            if (i > 0) j.append(',');
            Doc doc = docs.get(i);
            WritingAnalysis analysis = doc.writingAnalysis;
            j.append("{\"name\":").append(q(doc.name))
             .append(",\"score\":").append(analysis.score)
             .append(",\"label\":").append(q(analysis.label))
             .append(",\"note\":").append(q(analysis.note))
             .append(",\"signals\":[");
            for (int k = 0; k < analysis.signals.size(); k++) {
                if (k > 0) j.append(',');
                j.append(q(analysis.signals.get(k)));
            }
            j.append("]}");
        }
        j.append("],\"results\":[");
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            if (i > 0) j.append(',');
            j.append("{\"a\":").append(q(r.a.name)).append(",\"b\":").append(q(r.b.name))
             .append(String.format(Locale.US, ",\"simA\":%.2f,\"simB\":%.2f,\"overall\":%.2f,\"wordNgramOverlap\":%.2f", r.simA, r.simB, r.overall, r.wordNgramOverlap))
             .append(",\"verdict\":").append(q(verdict(r.overall)))
             .append(",\"hits\":").append(r.hits).append(",\"verified\":").append(r.verified)
             .append(",\"collisions\":").append(r.collisions)
             .append(",\"shortMatches\":").append(r.shortMatches)
             .append(",\"matches\":[");
            int pairIndex = 0;
            List<LinePairMatch> orderedPairs = new ArrayList<>(r.matches.values());
            orderedPairs.sort(Comparator.comparingInt((LinePairMatch p) -> p.aLine)
                                        .thenComparingInt(p -> p.bLine));
            for (LinePairMatch pair : orderedPairs) {
                String textA = matchedTextFromSpans(r.a.text, pair.spans, true);
                String textB = matchedTextFromSpans(r.b.text, pair.spans, false);
                if (textA.isEmpty() || textB.isEmpty()) continue;
                if (pairIndex++ > 0) j.append(',');
                j.append("{\"aLine\":").append(pair.aLine)
                 .append(",\"aText\":").append(q(textA))
                 .append(",\"bLine\":").append(pair.bLine)
                 .append(",\"bText\":").append(q(textB)).append("}");
            }
            j.append("]}");
        }
        return j.append("]}").toString();
    }


    // =====================================================================
    //  DOCUMENT TEXT EXTRACTION  (txt, docx, pptx, xlsx, odt/odp/ods, rtf,
    //  html, pdf, md, csv, json, xml, java, ...)
    // =====================================================================
    static final Set<String> SUPPORTED = new LinkedHashSet<>(Arrays.asList(
        "txt","md","csv","tsv","log","json","xml","java","py","c","cpp","js","css","tex",
        "html","htm","rtf","docx","dotx","pptx","xlsx","odt","odp","ods","pdf"));

    static String extractText(String fileName, byte[] data) throws Exception {
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : "txt";
        switch (ext) {
            case "docx": case "dotx":
                return zipRegex(data, n -> n.equals("word/document.xml") || n.matches("word/(header|footer|footnotes|endnotes)\\d*\\.xml"),
                                "<w:t(?:\\s[^>]*)?>([^<]*)</w:t>|</w:p>|<w:tab/>");
            case "pptx":
                return zipRegex(data, n -> n.matches("ppt/slides/slide\\d+\\.xml"),
                                "<a:t>([^<]*)</a:t>|</a:p>");
            case "xlsx":
                return zipRegex(data, n -> n.equals("xl/sharedStrings.xml") || n.matches("xl/worksheets/sheet\\d+\\.xml"),
                                "<t(?:\\s[^>]*)?>([^<]*)</t>|</si>|</row>");
            case "odt": case "odp": case "ods":
                return zipOpenDocument(data);
            case "pdf":
                return pdfText(data);
            case "rtf":
                return rtfText(new String(data, StandardCharsets.ISO_8859_1));
            case "html": case "htm":
                return htmlText(new String(data, StandardCharsets.UTF_8));
            case "doc": case "ppt": case "xls":
                throw new IOException("old binary ." + ext + " format is not supported - save it as ." + ext + "x (or PDF) and upload again");
            default:
                if (!SUPPORTED.contains(ext)) throw new IOException("unsupported file type ." + ext);
                return new String(data, StandardCharsets.UTF_8);
        }
    }

    /** Reads matching entries of a zip (docx/pptx/xlsx) and pulls text with the given regex. */
    static String zipRegex(byte[] data, java.util.function.Predicate<String> pick, String regex) throws IOException {
        TreeMap<String, byte[]> parts = new TreeMap<>(HashPlagServer::naturalCompare);
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null)
                if (pick.test(e.getName())) parts.put(e.getName(), z.readAllBytes());
        } catch (ZipException ze) {
            throw new IOException("file is corrupted or not a valid Office document");
        }
        if (parts.isEmpty()) throw new IOException("no readable content found in file");
        Pattern pat = Pattern.compile(regex);
        StringBuilder out = new StringBuilder();
        for (byte[] part : parts.values()) {
            Matcher m = pat.matcher(new String(part, StandardCharsets.UTF_8));
            while (m.find()) out.append(m.group(1) != null ? unescape(m.group(1)) : (m.group().equals("<w:tab/>") ? " " : "\n"));
            out.append('\n');
        }
        return out.toString();
    }

    static String zipOpenDocument(byte[] data) throws IOException {
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().equals("content.xml")) {
                    String x = new String(z.readAllBytes(), StandardCharsets.UTF_8);
                    x = x.replaceAll("</text:(p|h)>|<text:line-break/>|<text:tab/>", "\n");
                    return unescape(x.replaceAll("<[^>]+>", ""));
                }
            }
        }
        throw new IOException("no readable content found in file");
    }

    static String htmlText(String h) {
        h = h.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("(?s)<!--.*?-->", " ");
        h = h.replaceAll("(?i)</?(p|div|br|li|tr|h[1-6]|table|section|article)[^>]*>", "\n");
        return unescape(h.replaceAll("<[^>]+>", " "));
    }

    static String rtfText(String r) {
        Set<String> skipWords = new HashSet<>(Arrays.asList("fonttbl","colortbl","stylesheet","info","pict","header","footer","generator","listtable","listoverridetable","rsidtbl"));
        StringBuilder out = new StringBuilder();
        int depth = 0, skipDepth = -1, n = r.length();
        for (int i = 0; i < n; i++) {
            char c = r.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { if (skipDepth == depth) skipDepth = -1; depth--; }
            else if (c == '\\' && i + 1 < n) {
                char d = r.charAt(i + 1);
                if (d == '\\' || d == '{' || d == '}') { if (skipDepth < 0) out.append(d); i++; }
                else if (d == '\'' && i + 3 < n) {
                    try { int v = Integer.parseInt(r.substring(i + 2, i + 4), 16);
                          if (skipDepth < 0) out.append(new String(new byte[]{(byte) v}, "windows-1252")); } catch (Exception ignored) {}
                    i += 3;
                } else if (d == '*') { if (skipDepth < 0) skipDepth = depth; i++; }
                else if (Character.isLetter(d)) {
                    int j = i + 1;
                    while (j < n && Character.isLetter(r.charAt(j))) j++;
                    String word = r.substring(i + 1, j);
                    int k = j;
                    if (k < n && (r.charAt(k) == '-' || Character.isDigit(r.charAt(k)))) { k++; while (k < n && Character.isDigit(r.charAt(k))) k++; }
                    String param = r.substring(j, k);
                    if (k < n && r.charAt(k) == ' ') k++;
                    if (skipWords.contains(word) && skipDepth < 0) skipDepth = depth;
                    else if (skipDepth < 0) {
                        if (word.equals("par") || word.equals("line") || word.equals("sect")) out.append('\n');
                        else if (word.equals("tab")) out.append(' ');
                        else if (word.equals("u") && !param.isEmpty()) {
                            int cp = Integer.parseInt(param); if (cp < 0) cp += 65536;
                            out.append((char) cp);
                            if (k < n) k++;                      // skip fallback char
                        }
                    }
                    i = k - 1;
                } else i++;
            } else if (skipDepth < 0 && c != '\r' && c != '\n') out.append(c);
        }
        return out.toString();
    }

    /** PDF text via Apache PDFBox (jar in the lib/ folder), loaded by reflection. */
    static String pdfText(byte[] data) throws Exception {
        Class<?> docC, strC;
        try {
            docC = Class.forName("org.apache.pdfbox.pdmodel.PDDocument");
            strC = Class.forName("org.apache.pdfbox.text.PDFTextStripper");
        } catch (ClassNotFoundException e) {
            throw new IOException("PDF support needs the PDFBox jar in the 'lib' folder (see README)");
        }
        Object doc = null;
        try {
            doc = docC.getMethod("load", byte[].class).invoke(null, (Object) data);
            Object stripper = strC.getDeclaredConstructor().newInstance();
            return (String) strC.getMethod("getText", docC).invoke(stripper, doc);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable t = e.getCause() != null ? e.getCause() : e;
            throw new IOException("cannot read PDF (" + t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "") + ")");
        } finally {
            if (doc != null) try { docC.getMethod("close").invoke(doc); } catch (Exception ignored) {}
        }
    }

    static String unescape(String s) {
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ");
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            try {
                int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
            } catch (Exception e) { m.appendReplacement(sb, " "); }
        }
        m.appendTail(sb);
        return sb.toString().replace("&amp;", "&");
    }

    static int naturalCompare(String a, String b) {   // slide2 before slide10
        Matcher x = Pattern.compile("\\d+").matcher(a), y = Pattern.compile("\\d+").matcher(b);
        if (x.find() && y.find() && a.replaceAll("\\d+", "").equals(b.replaceAll("\\d+", "")))
            return Long.compare(Long.parseLong(x.group()), Long.parseLong(y.group()));
        return a.compareTo(b);
    }

    // ================= HTTP handlers, sessions, history, reports, live search =================
    static Path webDir;
    static Path dataDir;
    static Path historyDir;
    static final AtomicInteger PROGRESS = new AtomicInteger(0);
    static volatile String PROGRESS_MESSAGE = "Ready";
    static volatile String PROGRESS_OWNER = "";

    static class UserSession {
        final String username;
        final String role;
        UserSession(String username, String role) { this.username = username; this.role = role; }
    }
    static final ConcurrentMap<String, UserSession> SESSIONS = new ConcurrentHashMap<>();
    static final Set<String> CANCELLED_USERS = ConcurrentHashMap.newKeySet();

    static void checkScanCancelled(UserSession user) {
        if (user != null && CANCELLED_USERS.remove(user.username))
            throw new java.util.concurrent.CancellationException("Scan cancelled by user.");
    }

    static void progress(int value, String message, String owner) {
        PROGRESS.set(Math.max(0, Math.min(100, value)));
        PROGRESS_MESSAGE = message;
        PROGRESS_OWNER = owner == null ? "" : owner;
    }

    static byte[] readLimited(java.io.InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0, n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) throw new IOException("Request too large. Maximum allowed request size is " + (maxBytes / (1024 * 1024)) + " MB.");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static Map<String, List<String>> parseForm(String body) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) return map;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(eq < 0 ? "" : pair.substring(eq + 1), StandardCharsets.UTF_8);
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return map;
    }

    static String first(Map<String, List<String>> map, String key, String fallback) {
        List<String> list = map.get(key);
        return list == null || list.isEmpty() ? fallback : list.get(0);
    }

    static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Referrer-Policy", "same-origin");
        if (type.startsWith("application/json") || type.startsWith("application/pdf")) ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    static void json(HttpExchange ex, int code, String body) throws IOException {
        send(ex, code, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    static String cookieValue(HttpExchange ex, String key) {
        List<String> cookies = ex.getRequestHeaders().get("Cookie");
        if (cookies == null) return "";
        for (String cookieHeader : cookies) {
            for (String part : cookieHeader.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(key)) return kv[1];
            }
        }
        return "";
    }

    static UserSession session(HttpExchange ex) {
        String token = cookieValue(ex, "hashplag_session");
        return token.isEmpty() ? null : SESSIONS.get(token);
    }

    static boolean requireLogin(HttpExchange ex) throws IOException {
        if (session(ex) != null) return true;
        json(ex, 401, "{\"error\":\"Please sign in to use HashPlag.\",\"authRequired\":true}");
        return false;
    }

    static String configuredPassword(String username) {
        if (username.equals("admin")) return System.getenv().getOrDefault("HASHPLAG_ADMIN_PASSWORD", "admin123");
        if (username.equals("faculty")) return System.getenv().getOrDefault("HASHPLAG_FACULTY_PASSWORD", "faculty123");
        if (username.equals("student")) return System.getenv().getOrDefault("HASHPLAG_STUDENT_PASSWORD", "student123");
        return null;
    }

    static String roleFor(String username) {
        if (username.equals("admin")) return "ADMIN";
        if (username.equals("faculty")) return "FACULTY";
        if (username.equals("student")) return "STUDENT";
        return "";
    }

    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static void handleLogin(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"Use POST\"}"); return; }
        Map<String, List<String>> form = parseForm(new String(readLimited(ex.getRequestBody(), 64 * 1024), StandardCharsets.UTF_8));
        String username = first(form, "username", "").trim().toLowerCase(Locale.ROOT);
        String password = first(form, "password", "");
        String expected = configuredPassword(username);
        if (expected == null || !constantTimeEquals(expected, password)) {
            json(ex, 401, "{\"error\":\"Invalid username or password.\"}"); return;
        }
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        SESSIONS.put(token, new UserSession(username, roleFor(username)));
        String cookie = "hashplag_session=" + token + "; Path=/; HttpOnly; SameSite=Lax";
        if (Boolean.parseBoolean(System.getenv().getOrDefault("HASHPLAG_COOKIE_SECURE", "false"))) cookie += "; Secure";
        ex.getResponseHeaders().add("Set-Cookie", cookie);
        json(ex, 200, "{\"authenticated\":true,\"username\":" + q(username) + ",\"role\":" + q(roleFor(username)) + "}");
    }

    static void handleLogout(HttpExchange ex) throws IOException {
        String token = cookieValue(ex, "hashplag_session");
        if (!token.isEmpty()) SESSIONS.remove(token);
        String cookie = "hashplag_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0";
        if (Boolean.parseBoolean(System.getenv().getOrDefault("HASHPLAG_COOKIE_SECURE", "false"))) cookie += "; Secure";
        ex.getResponseHeaders().add("Set-Cookie", cookie);
        json(ex, 200, "{\"authenticated\":false}");
    }

    static void handleMe(HttpExchange ex) throws IOException {
        UserSession user = session(ex);
        if (user == null) { json(ex, 200, "{\"authenticated\":false}"); return; }
        json(ex, 200, "{\"authenticated\":true,\"username\":" + q(user.username) + ",\"role\":" + q(user.role) + "}");
    }

    /** POST form: window=30&mode=text|code&name=file&data=base64 ... */
    static void handleCompare(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { json(ex, 405, "{\"error\":\"Use POST\"}"); return; }
        if (!requireLogin(ex)) return;
        UserSession user = session(ex);
        CANCELLED_USERS.remove(user.username);
        progress(1, "Receiving files", user.username);
        try {
            String body = new String(readLimited(ex.getRequestBody(), 45 * 1024 * 1024), StandardCharsets.UTF_8);
            Map<String, List<String>> form = parseForm(body);
            int w = Math.max(10, Math.min(100, Integer.parseInt(first(form, "window", "30"))));
            String mode = first(form, "mode", "text").equalsIgnoreCase("code") ? "code" : "text";
            List<String> names = form.getOrDefault("name", Collections.emptyList());
            List<String> datas = form.getOrDefault("data", Collections.emptyList());
            if (names.size() != datas.size() || names.size() < 2) {
                json(ex, 400, "{\"error\":\"Select at least two supported documents.\"}"); return;
            }
            List<String> warnings = new ArrayList<>();
            List<Doc> docs = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                checkScanCancelled(user);
                progress(5 + (int)(25.0 * i / Math.max(1, names.size())), "Extracting " + names.get(i), user.username);
                String name = Paths.get(names.get(i)).getFileName().toString();
                try {
                    String sourceText = extractText(name, Base64.getDecoder().decode(datas.get(i)));
                    String text = mode.equals("code") ? preprocessCode(sourceText, name) : preprocess(sourceText);
                    if (text.trim().isEmpty()) warnings.add(name + ": no readable text found (scanned/image-only file?) - skipped");
                    else docs.add(new Doc(name, text, sourceText));
                } catch (Exception e) {
                    warnings.add(name + ": " + String.valueOf(e.getMessage()) + " - skipped");
                }
            }
            if (docs.size() < 2) {
                progress(0, "Not enough readable documents", user.username);
                StringBuilder msg = new StringBuilder("Need at least 2 readable documents.");
                for (String wn : warnings) msg.append(' ').append(wn).append('.');
                json(ex, 400, "{\"error\":" + q(msg.toString()) + "}"); return;
            }
            List<Result> results = new ArrayList<>();
            int total = docs.size() * (docs.size() - 1) / 2;
            int done = 0;
            for (int i = 0; i < docs.size(); i++) {
                for (int k = i + 1; k < docs.size(); k++) {
                    checkScanCancelled(user);
                    progress(30 + (int)(55.0 * done / Math.max(1, total)), "Comparing " + docs.get(i).name + " with " + docs.get(k).name, user.username);
                    results.add(compare(docs.get(i), docs.get(k), w));
                    done++;
                }
            }
            String reportJson = toJson(docs, results, w, warnings, mode);
            progress(90, "Saving scan history", user.username);
            saveHistory(user, reportJson, docs, results);
            progress(100, "Comparison complete", user.username);
            json(ex, 200, reportJson);
        } catch (java.util.concurrent.CancellationException cancelled) {
            progress(0, "Scan cancelled", user.username);
            json(ex, 409, "{\"error\":\"Scan cancelled by user.\",\"cancelled\":true}");
        } catch (Exception e) {
            progress(0, "Comparison failed", session(ex) == null ? "" : session(ex).username);
            json(ex, 500, "{\"error\":" + q(String.valueOf(e.getMessage())) + "}");
        }
    }

    static void handleCancel(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { json(ex,405,"{\"error\":\"Use POST\"}"); return; }
        if (!requireLogin(ex)) return;
        UserSession user = session(ex);
        CANCELLED_USERS.add(user.username);
        json(ex,200,"{\"requested\":true,\"message\":\"Cancellation requested. It will stop at the next processing checkpoint.\"}");
    }

    static void handleProgress(HttpExchange ex) throws IOException {
        UserSession user = session(ex);
        if (user == null) { json(ex, 401, "{\"error\":\"Sign in required\"}"); return; }
        json(ex, 200, "{\"percent\":" + PROGRESS.get() + ",\"message\":" + q(PROGRESS_MESSAGE) + "}");
    }

    static double averageSimilarity(List<Result> results) {
        if (results.isEmpty()) return 0;
        double sum = 0;
        for (Result r : results) sum += r.overall;
        return sum / results.size();
    }

    static void saveHistory(UserSession user, String reportJson, List<Doc> docs, List<Result> results) throws IOException {
        Files.createDirectories(historyDir);
        String id = Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8);
        String docsText = String.join(", ", docs.stream().map(d -> d.name).toArray(String[]::new));
        Properties meta = new Properties();
        meta.setProperty("id", id);
        meta.setProperty("owner", user.username);
        meta.setProperty("role", user.role);
        meta.setProperty("createdAt", Instant.now().toString());
        meta.setProperty("documents", docsText);
        meta.setProperty("pairs", Integer.toString(results.size()));
        meta.setProperty("averageSimilarity", String.format(Locale.US, "%.2f", averageSimilarity(results)));
        Files.write(historyDir.resolve(id + ".json"), reportJson.getBytes(StandardCharsets.UTF_8));
        try (OutputStream os = Files.newOutputStream(historyDir.resolve(id + ".properties"))) { meta.store(os, "HashPlag scan metadata"); }
    }

    static List<Properties> historyEntries(UserSession requester) throws IOException {
        List<Properties> list = new ArrayList<>();
        if (!Files.isDirectory(historyDir)) return list;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(historyDir, "*.properties")) {
            for (Path path : stream) {
                Properties p = new Properties();
                try (java.io.InputStream in = Files.newInputStream(path)) { p.load(in); }
                if (!requester.role.equals("ADMIN") && !requester.role.equals("FACULTY") && !requester.username.equals(p.getProperty("owner"))) continue;
                list.add(p);
            }
        }
        list.sort((a,b) -> b.getProperty("createdAt", "").compareTo(a.getProperty("createdAt", "")));
        return list;
    }

    static String historyJson(List<Properties> entries) {
        StringBuilder j = new StringBuilder("{\"history\":[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) j.append(',');
            Properties p = entries.get(i);
            j.append("{\"id\":").append(q(p.getProperty("id", "")))
             .append(",\"owner\":").append(q(p.getProperty("owner", "")))
             .append(",\"createdAt\":").append(q(p.getProperty("createdAt", "")))
             .append(",\"documents\":").append(q(p.getProperty("documents", "")))
             .append(",\"pairs\":").append(Integer.parseInt(p.getProperty("pairs", "0")))
             .append(",\"averageSimilarity\":").append(p.getProperty("averageSimilarity", "0.00"))
             .append('}');
        }
        return j.append("]}").toString();
    }

    static void handleHistory(HttpExchange ex) throws IOException {
        if (!requireLogin(ex)) return;
        UserSession user = session(ex);
        String path = ex.getRequestURI().getPath();
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            String query = ex.getRequestURI().getRawQuery();
            if (query != null && query.contains("id=")) {
                Map<String,List<String>> q = parseForm(query);
                String id = first(q, "id", "");
                if (!id.matches("[A-Za-z0-9_-]{1,80}")) { json(ex,400,"{\"error\":\"Invalid history id\"}"); return; }
                Path metaPath = historyDir.resolve(id + ".properties");
                Path reportPath = historyDir.resolve(id + ".json");
                if (!Files.isRegularFile(metaPath) || !Files.isRegularFile(reportPath)) { json(ex,404,"{\"error\":\"History report not found\"}"); return; }
                Properties meta = new Properties();
                try (java.io.InputStream in = Files.newInputStream(metaPath)) { meta.load(in); }
                if (!user.role.equals("ADMIN") && !user.role.equals("FACULTY") && !user.username.equals(meta.getProperty("owner"))) { json(ex,403,"{\"error\":\"You cannot open this report\"}"); return; }
                send(ex,200,"application/json; charset=utf-8",Files.readAllBytes(reportPath)); return;
            }
            json(ex, 200, historyJson(historyEntries(user))); return;
        }
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            Map<String,List<String>> form = parseForm(new String(readLimited(ex.getRequestBody(), 64 * 1024), StandardCharsets.UTF_8));
            String id = first(form,"id","");
            if (!id.matches("[A-Za-z0-9_-]{1,80}")) { json(ex,400,"{\"error\":\"Invalid history id\"}"); return; }
            Path metaPath = historyDir.resolve(id + ".properties"), reportPath = historyDir.resolve(id + ".json");
            if (!Files.isRegularFile(metaPath)) { json(ex,404,"{\"error\":\"History report not found\"}"); return; }
            Properties meta = new Properties();
            try (java.io.InputStream in = Files.newInputStream(metaPath)) { meta.load(in); }
            if (!user.role.equals("ADMIN") && !user.username.equals(meta.getProperty("owner"))) { json(ex,403,"{\"error\":\"Only the owner or an administrator can delete this scan\"}"); return; }
            Files.deleteIfExists(metaPath); Files.deleteIfExists(reportPath);
            json(ex,200,"{\"deleted\":true}"); return;
        }
        json(ex,405,"{\"error\":\"Use GET or POST\"}");
    }

    static String pdfSafe(String s) {
        return s.replace("↔", "<->").replace("—", "-").replace("–", "-").replace("·", "-")
                .replaceAll("[^\\x20-\\x7E]", "?");
    }

    static byte[] makePdf(String reportText) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String rawLine : reportText.replace("\r", "").split("\n", -1)) {
            String line = pdfSafe(rawLine);
            if (line.isEmpty()) { lines.add(""); continue; }
            while (line.length() > 92) {
                int cut = line.lastIndexOf(' ', 92);
                if (cut < 25) cut = 92;
                lines.add(line.substring(0, cut));
                line = line.substring(Math.min(line.length(), cut + 1));
            }
            lines.add(line);
        }
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            int index = 0;
            while (index < lines.size() || pdf.getNumberOfPages() == 0) {
                PDPage page = new PDPage(); pdf.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA_BOLD, 15); cs.newLineAtOffset(50, 750);
                    cs.showText("HashPlag Detect - Similarity Report"); cs.endText();
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 9); cs.newLineAtOffset(50, 725);
                    int count = 0;
                    while (index < lines.size() && count < 44) {
                        cs.showText(lines.get(index).isEmpty() ? " " : lines.get(index));
                        cs.newLineAtOffset(0, -14); index++; count++;
                    }
                    cs.endText();
                }
            }
            pdf.save(bytes); return bytes.toByteArray();
        }
    }

    static void handleReport(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { json(ex,405,"{\"error\":\"Use POST\"}"); return; }
        if (!requireLogin(ex)) return;
        try {
            Map<String,List<String>> form = parseForm(new String(readLimited(ex.getRequestBody(), 2 * 1024 * 1024), StandardCharsets.UTF_8));
            String content = first(form,"content","");
            if (content.trim().isEmpty()) { json(ex,400,"{\"error\":\"Report content is empty\"}"); return; }
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=HashPlag_Report.pdf");
            send(ex,200,"application/pdf",makePdf(content));
        } catch (Exception e) { json(ex,500,"{\"error\":" + q(e.getMessage() == null ? "PDF generation failed" : e.getMessage()) + "}"); }
    }

    static String stripHtml(String s) {
        return s.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
                .replaceAll("\\s+", " ").trim();
    }

    static String decodeSearchUrl(String href) {
        String h = href.replace("&amp;", "&");
        if (h.startsWith("//")) h = "https:" + h;
        if (h.contains("duckduckgo.com/l/?")) {
            try {
                String raw = URI.create(h).getRawQuery();
                if (raw != null) for (String pair : raw.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0 && pair.substring(0,eq).equals("uddg")) return URLDecoder.decode(pair.substring(eq+1), StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) { }
        }
        return h;
    }

    static String webSearchJson(String query) throws IOException {
        String searchUrl = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode("\"" + query + "\"", StandardCharsets.UTF_8);
        String html;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URI(searchUrl).toURL().openConnection();
            conn.setRequestMethod("GET"); conn.setConnectTimeout(4500); conn.setReadTimeout(4500);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 HashPlag-SourceLookup/1.0");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            try (java.io.InputStream in = conn.getInputStream()) { html = new String(in.readNBytes(1_500_000), StandardCharsets.UTF_8); }
        } catch (Exception e) {
            return "{\"query\":" + q(query) + ",\"available\":false,\"message\":" + q("Live web search could not be reached. You can open the search link below.") + ",\"searchUrl\":" + q(searchUrl) + ",\"results\":[]}";
        } finally { if (conn != null) conn.disconnect(); }
        List<String> results = new ArrayList<>();
        Pattern anchorPattern = Pattern.compile("(?is)<a\\b([^>]*)>(.*?)</a>");
        Matcher anchors = anchorPattern.matcher(html);
        Set<String> seen = new HashSet<>();
        while (anchors.find() && results.size() < 8) {
            String attrs = anchors.group(1);
            if (!attrs.contains("result__a") && !attrs.contains("result-link")) continue;
            Matcher hm = Pattern.compile("(?i)href\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]").matcher(attrs);
            if (!hm.find()) continue;
            String url = decodeSearchUrl(hm.group(1));
            String title = stripHtml(anchors.group(2));
            if (title.isEmpty() || !url.startsWith("http") || url.contains("duckduckgo.com")) continue;
            if (!seen.add(url)) continue;
            results.add("{\"title\":" + q(title) + ",\"url\":" + q(url) + "}");
        }
        return "{\"query\":" + q(query) + ",\"available\":true,\"message\":" + q(results.isEmpty() ? "Search completed, but no parseable results were returned. Try opening the web search link." : "Possible public web sources. Results must be manually verified.") + ",\"searchUrl\":" + q(searchUrl) + ",\"results\":[" + String.join(",", results) + "]}";
    }

    static void handleWebSearch(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { json(ex,405,"{\"error\":\"Use POST\"}"); return; }
        if (!requireLogin(ex)) return;
        Map<String,List<String>> form = parseForm(new String(readLimited(ex.getRequestBody(), 64 * 1024), StandardCharsets.UTF_8));
        String query = first(form,"query","").replaceAll("\\s+", " ").trim();
        if (query.length() < 12) { json(ex,400,"{\"error\":\"Enter a phrase of at least 12 characters to search for possible sources.\"}"); return; }
        if (query.length() > 180) query = query.substring(0,180);
        json(ex,200,webSearchJson(query));
    }

    static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path f = webDir.resolve(path.substring(1)).normalize();
        if (!f.startsWith(webDir) || !Files.isRegularFile(f)) { send(ex,404,"text/plain; charset=utf-8","Not found".getBytes(StandardCharsets.UTF_8)); return; }
        String type = path.endsWith(".html") ? "text/html; charset=utf-8"
                    : path.endsWith(".css") ? "text/css; charset=utf-8"
                    : path.endsWith(".js") ? "application/javascript; charset=utf-8"
                    : "application/octet-stream";
        byte[] content = Files.readAllBytes(f);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        send(ex,200,type,content);
    }

    // ================= Main =================
    public static void main(String[] args) throws Exception {
        for (String c : new String[]{"web", "../web", "HashPlagWeb/web"}) {
            Path p = Paths.get(c).toAbsolutePath().normalize();
            if (Files.isRegularFile(p.resolve("index.html"))) { webDir = p; break; }
        }
        if (webDir == null) {
            System.out.println("Cannot find the 'web' folder (index.html). Open the HashPlagWeb folder in VS Code and run again."); return;
        }
        String configuredData = System.getenv().getOrDefault("HASHPLAG_DATA_DIR", "data");
        dataDir = Paths.get(configuredData).toAbsolutePath().normalize();
        historyDir = dataDir.resolve("history");
        Files.createDirectories(historyDir);

        String portEnv = System.getenv("PORT");
        int port;
        HttpServer server;
        if (portEnv != null && !portEnv.trim().isEmpty()) {
            port = Integer.parseInt(portEnv.trim());
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        } else {
            port = 8080; server = null;
            for (; port < 8090 && server == null; port++) {
                try { server = HttpServer.create(new InetSocketAddress(port), 0); }
                catch (IOException busy) { }
            }
            port--;
            if (server == null) { System.out.println("No free port between 8080-8089."); return; }
        }
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        server.createContext("/api/login", HashPlagServer::handleLogin);
        server.createContext("/api/logout", HashPlagServer::handleLogout);
        server.createContext("/api/me", HashPlagServer::handleMe);
        server.createContext("/api/compare", HashPlagServer::handleCompare);
        server.createContext("/api/cancel", HashPlagServer::handleCancel);
        server.createContext("/api/progress", HashPlagServer::handleProgress);
        server.createContext("/api/history", HashPlagServer::handleHistory);
        server.createContext("/api/report", HashPlagServer::handleReport);
        server.createContext("/api/web-search", HashPlagServer::handleWebSearch);
        server.createContext("/", HashPlagServer::handleStatic);
        server.start();

        String url = "http://localhost:" + port;
        System.out.println("HashPlag Detect running at " + url + "   (press Ctrl+C / Stop to quit)");
        System.out.println("Serving frontend from: " + webDir);
        System.out.println("Data/history directory: " + dataDir);
        if (portEnv == null && !"false".equalsIgnoreCase(System.getenv().getOrDefault("OPEN_BROWSER", "true"))) {
            try { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(new URI(url)); }
            catch (Throwable ignored) { System.out.println("Browser could not be opened automatically; open " + url + " manually."); }
        }
        System.out.println("Local demo accounts: admin/admin123, faculty/faculty123, student/student123 (set environment passwords before public deployment).");
    }
}
