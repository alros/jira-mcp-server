package eu.rossotto;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Jira wiki markup (Server / Data Center, REST API v2) to plain text.
 *
 * <p>Best effort and deliberately lossy: the output feeds a summariser, so formatting is dropped,
 * links keep only their label, images and attachments disappear, and code blocks keep their content.
 *
 * @see <a href="https://jira.atlassian.com/secure/WikiRendererHelpAction.jspa?section=all">Text formatting notation</a>
 */
final class WikiToText {

    private static final Pattern CODE_BLOCK =
            Pattern.compile("\\{(code|noformat)(?::[^}]*)?}(.*?)\\{\\1}", Pattern.DOTALL);
    private static final Pattern MONOSPACE = Pattern.compile("\\{\\{(.+?)}}");
    private static final Pattern IMAGE = Pattern.compile(
            "!(?=[^\\s!])[^!\\n]+?(?:\\.(?:png|jpe?g|gif|svg|bmp|webp|tiff?|heic)|\\|[^!\\n]*)!",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern USER_LINK = Pattern.compile("\\[~(?:accountid:)?([^\\]]+)]");
    private static final Pattern LABELLED_LINK = Pattern.compile("\\[([^\\]|\\n]*)\\|([^\\]\\n]+)]");
    private static final Pattern BARE_LINK = Pattern.compile(
            "\\[(?:[#^]([^\\]|\\n]+)|((?:https?|ftp|file)://[^\\]|\\n]+|mailto:[^\\]|\\n]+))]");
    private static final Pattern MACRO = Pattern.compile("\\{[a-zA-Z]+(?::[^}\\n]*)?}");
    private static final Pattern HEADING = Pattern.compile("(?m)^[ \\t]*(?:h[1-6]|bq)\\.\\s*");
    private static final Pattern RULE = Pattern.compile("(?m)^[ \\t]*-{4,}[ \\t]*$");
    private static final Pattern LIST = Pattern.compile("(?m)^[ \\t]*([*#-]+)\\s+");
    private static final Pattern TABLE_ROW = Pattern.compile("(?m)^[ \\t]*\\|\\|?(.*?)\\|*[ \\t]*$");
    private static final Pattern CELL_SEPARATOR = Pattern.compile("\\s*\\|\\|?\\s*");
    private static final Pattern CITATION = Pattern.compile("\\?\\?(.+?)\\?\\?");
    private static final Pattern LINE_BREAK = Pattern.compile("\\\\\\\\");
    private static final Pattern PLACEHOLDER = Pattern.compile("\u0000(\\d+)\u0000");

    /** *bold* _italic_ -strike- +underline+ ^sup^ ~sub~ : the markers must hug non-space text. */
    private static final List<Pattern> EFFECTS = List.of('*', '_', '-', '+', '^', '~').stream()
            .map(c -> Pattern.quote(String.valueOf(c)))
            .map(q -> Pattern.compile("(?<![\\w" + q + "])" + q + "(?=\\S)([^\\n]+?)(?<=\\S)" + q + "(?![\\w" + q + "])"))
            .toList();

    private static final String[][] EMOTICONS = {
            {"(/)", "✓"}, {"(x)", "✗"}, {"(!)", "!"}, {"(?)", "?"}, {"(i)", "ℹ"}, {"(on)", ""},
            {"(off)", ""}, {"(*)", "*"}, {"(y)", "👍"}, {"(n)", "👎"},
    };

    private WikiToText() {
    }

    static String convert(String wiki) {
        if (wiki == null || wiki.isBlank()) {
            return "";
        }
        // 1. Shelter code blocks so their content is not interpreted as markup.
        List<String> sheltered = new ArrayList<>();
        String s = replace(CODE_BLOCK, wiki, m -> {
            sheltered.add(m.group(2).strip());
            return "\n\u0000" + (sheltered.size() - 1) + "\u0000\n";
        });
        s = replace(MONOSPACE, s, m -> {
            sheltered.add(m.group(1));
            return "\u0000" + (sheltered.size() - 1) + "\u0000";
        });

        // 2. Drop images / attachments, flatten links.
        s = IMAGE.matcher(s).replaceAll("");
        s = replace(USER_LINK, s, m -> m.group(1));
        s = replace(LABELLED_LINK, s, m -> m.group(1).isBlank() ? m.group(2).strip() : m.group(1).strip());
        s = replace(BARE_LINK, s, m -> (m.group(1) != null ? m.group(1) : m.group(2)).strip());

        // 3. Remove macros ({quote}, {panel:title=x}, {color:red}...) and block prefixes.
        s = MACRO.matcher(s).replaceAll("");
        s = HEADING.matcher(s).replaceAll("");
        s = RULE.matcher(s).replaceAll("");
        s = replace(LIST, s, m -> {
            String marks = m.group(1);
            return "  ".repeat(marks.length() - 1) + "- ";
        });
        s = replace(TABLE_ROW, s, m -> CELL_SEPARATOR.matcher(m.group(1).strip()).replaceAll("\t"));

        // 4. Inline effects and symbols.
        for (Pattern effect : EFFECTS) {
            s = replace(effect, s, m -> m.group(1));
        }
        s = replace(CITATION, s, m -> m.group(1));
        s = LINE_BREAK.matcher(s).replaceAll("\n");
        for (String[] emoticon : EMOTICONS) {
            s = s.replace(emoticon[0], emoticon[1]);
        }

        // 5. Restore sheltered code.
        return replace(PLACEHOLDER, s, m -> sheltered.get(Integer.parseInt(m.group(1))));
    }

    private static String replace(Pattern p, String input, Function<MatchResult, String> f) {
        return p.matcher(input).replaceAll(m -> Matcher.quoteReplacement(f.apply(m)));
    }
}
