package com.wpsbox.cfg;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deliberately small YAML reader/writer for the subset we actually use:
 *
 *   key: value
 *   key:
 *     nested: value
 *   list:
 *     - item
 *     - item
 *   # comment
 *
 * Supports scalars, nested maps, and lists of scalars. No anchors, no
 * multi-document, no flow style. That keeps the whole thing dependency-free,
 * which matters because we cannot ship a YAML library into a container that
 * already has a 238 MiB payload budget.
 */
public final class MiniYaml {

    /** Parsed document. Ordered so a round-trip rewrite stays readable. */
    public static final class Doc {
        public final Map<String, Object> root = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        public Map<String, Object> map(String key) {
            Object v = root.get(key);
            if (v instanceof Map) return (Map<String, Object>) v;
            Map<String, Object> m = new LinkedHashMap<>();
            root.put(key, m);
            return m;
        }

        public String str(String path, String def) {
            Object v = get(path);
            return v == null ? def : String.valueOf(v);
        }

        public int integer(String path, int def) {
            Object v = get(path);
            if (v == null) return def;
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }

        public boolean bool(String path, boolean def) {
            Object v = get(path);
            if (v == null) return def;
            String s = String.valueOf(v).trim().toLowerCase();
            // Accept both the human form and the 1/0 form the user asked for.
            if (s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("on")) return true;
            if (s.equals("0") || s.equals("false") || s.equals("no") || s.equals("off")) return false;
            return def;
        }

        /** Dot-separated lookup, e.g. "cef.enable". */
        @SuppressWarnings("unchecked")
        public Object get(String path) {
            String[] parts = path.split("\\.");
            Object cur = root;
            for (String p : parts) {
                if (!(cur instanceof Map)) return null;
                cur = ((Map<String, Object>) cur).get(p);
                if (cur == null) return null;
            }
            return cur;
        }

        public void put(String path, Object value) {
            String[] parts = path.split("\\.");
            Map<String, Object> cur = root;
            for (int i = 0; i < parts.length - 1; i++) {
                Object nxt = cur.get(parts[i]);
                if (!(nxt instanceof Map)) {
                    nxt = new LinkedHashMap<String, Object>();
                    cur.put(parts[i], nxt);
                }
                cur = (Map<String, Object>) nxt;
            }
            cur.put(parts[parts.length - 1], value);
        }
    }

    private MiniYaml() {}

    // ---------------------------------------------------------------- parse

    public static Doc parse(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            r.close();
        }
        return parse(sb.toString());
    }

    public static Doc parse(String text) {
        Doc doc = new Doc();
        // stack of (indent, container)
        List<int[]> indents = new ArrayList<>();
        List<Object> containers = new ArrayList<>();
        indents.add(new int[]{-1});
        containers.add(doc.root);

        String[] lines = text.split("\n", -1);
        for (String raw : lines) {
            String noComment = stripComment(raw);
            if (noComment.trim().isEmpty()) continue;

            int indent = 0;
            while (indent < noComment.length() && noComment.charAt(indent) == ' ') indent++;
            String body = noComment.substring(indent).trim();
            if (body.isEmpty()) continue;

            // Pop containers that this line is not nested under.
            while (indents.size() > 1 && indent <= indents.get(indents.size() - 1)[0]) {
                indents.remove(indents.size() - 1);
                containers.remove(containers.size() - 1);
            }
            Object parent = containers.get(containers.size() - 1);

            if (body.startsWith("- ")) {
                String item = body.substring(2).trim();
                if (parent instanceof List) {
                    ((List<Object>) parent).add(scalar(item));
                }
                continue;
            }

            int colon = findColon(body);
            if (colon < 0) continue;
            String key = body.substring(0, colon).trim();
            String val = body.substring(colon + 1).trim();
            if (key.isEmpty()) continue;

            if (val.isEmpty()) {
                // Could become a map or a list; decide by peeking the next line.
                Object child = peekIsList(lines, raw) ? new ArrayList<Object>()
                                                      : new LinkedHashMap<String, Object>();
                putInto(parent, key, child);
                indents.add(new int[]{indent});
                containers.add(child);
            } else {
                putInto(parent, key, scalar(val));
            }
        }
        return doc;
    }

    private static boolean peekIsList(String[] lines, String current) {
        boolean after = false;
        for (String l : lines) {
            if (!after) {
                if (l == current) after = true;
                continue;
            }
            String s = stripComment(l).trim();
            if (s.isEmpty()) continue;
            return s.startsWith("- ");
        }
        return false;
    }

    private static void putInto(Object parent, String key, Object value) {
        if (parent instanceof Map) {
            ((Map<String, Object>) parent).put(key, value);
        }
    }

    private static String stripComment(String line) {
        boolean inS = false, inD = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inD) inS = !inS;
            else if (c == '"' && !inS) inD = !inD;
            else if (c == '#' && !inS && !inD) {
                if (i == 0 || line.charAt(i - 1) == ' ') return line.substring(0, i);
            }
        }
        return line;
    }

    private static int findColon(String s) {
        boolean inS = false, inD = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !inD) inS = !inS;
            else if (c == '"' && !inS) inD = !inD;
            else if (c == ':' && !inS && !inD) return i;
        }
        return -1;
    }

    private static Object scalar(String s) {
        String v = s.trim();
        if (v.length() >= 2) {
            char a = v.charAt(0), b = v.charAt(v.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }

    // ---------------------------------------------------------------- write

    public static void save(File f, Doc doc) throws Exception {
        StringBuilder sb = new StringBuilder();
        writeMap(sb, doc.root, 0);
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
        try {
            w.write(sb.toString());
        } finally {
            w.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeMap(StringBuilder sb, Map<String, Object> m, int indent) {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            pad(sb, indent);
            sb.append(e.getKey()).append(':');
            Object v = e.getValue();
            if (v instanceof Map) {
                sb.append('\n');
                writeMap(sb, (Map<String, Object>) v, indent + 2);
            } else if (v instanceof List) {
                sb.append('\n');
                for (Object o : (List<Object>) v) {
                    pad(sb, indent + 2);
                    sb.append("- ").append(String.valueOf(o)).append('\n');
                }
            } else {
                sb.append(' ').append(String.valueOf(v)).append('\n');
            }
        }
    }

    private static void pad(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) sb.append(' ');
    }
}
