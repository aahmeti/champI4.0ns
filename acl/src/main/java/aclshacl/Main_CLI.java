package aclshacl;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.Syntax;

public final class Main_CLI {
    private Main_CLI() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * CLI entry point.
     *
     * Required args:
     * - --user-uri <iri>
     * - --graph <iri>
     *
     * Optional args:
     * - --user-name <name> (defaults to the user IRI)
    * - --odrl-folder <path> (defaults to src/main/resources/odrl_test/)
    *   Aliases: --odrlFolder, --policy-folder
     */
    public static void main(String[] args) {
        try {
            Map<String, String> parsed = parseArgs(args);
            if (parsed.containsKey("help")) {
                System.out.println(usage());
                return;
            }

            String userUri = required(parsed, "user-uri");
            String userName = parsed.getOrDefault("user-name", userUri);
            String graphUri = required(parsed, "graph");

            Path odrlFolder = Path.of(getOdrlFolderArg(parsed));

            String rewritten = rewriteQueryForUserAndGraph(odrlFolder, userUri, userName, graphUri);
            System.out.println(rewritten);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            System.err.println(usage());
            System.exit(1);
        }
    }

    /**
     * Rewrites a fixed "graph dump" SPARQL query using ODRL policies for a specific user and named graph.
     *
     * The input query is always:
     * {@code SELECT ?s ?p ?o WHERE { GRAPH <graphUri> { ?s ?p ?o } }}
     *
     * This will:
     * 1) Parse + normalize the query (expects exactly one target graph in GRAPH/FROM clauses).
     * 2) Apply ODRL permission/prohibition rewriting using the provided graph and user.
     *
     * @return the rewritten SPARQL query string
     */
    public static String rewriteQueryForUserAndGraph(
        Path odrlFolder,
        String userUri,
        String userName,
        String graphUri
    ) {
        if (odrlFolder == null) {
            throw new IllegalArgumentException("odrlFolder must not be null");
        }
        if (userUri == null || userUri.isBlank()) {
            throw new IllegalArgumentException("userUri must not be null/blank");
        }
        if (graphUri == null || graphUri.isBlank()) {
            throw new IllegalArgumentException("graphUri must not be null/blank");
        }

        User user = new User(userUri, (userName == null || userName.isBlank()) ? userUri : userName);
        Node graphIri = NodeFactory.createURI(graphUri);

        String originalQuery = "SELECT ?s ?p ?o WHERE { GRAPH <" + graphUri + "> { ?s ?p ?o } }";
        Query parsed = QueryFactory.create(originalQuery, Syntax.syntaxSPARQL_11);
        Query normalized = CoreUtils.normalizeQuery(parsed);
        if (normalized == null) {
            throw new IllegalArgumentException(
                "Query could not be normalized. It must reference exactly one graph via GRAPH <...> or FROM <...>."
            );
        }

        Query rewritten = CoreUtils.rewriteQueryWithOdrlPolicies(normalized.toString(), odrlFolder, graphIri, user);
        if (rewritten == null) {
            throw new IllegalStateException("Rewrite returned null");
        }
        return rewritten.toString();
    }

    private static String usage() {
        return "Usage:\n"
            + "  java ... aclshacl.Main_CLI --user-uri <iri> --graph <iri> [--user-name <name>] [--odrl-folder <path>]\n"
            + "\n"
            + "Examples:\n"
            + "  java ... aclshacl.Main_CLI \\\n"
            + "    --user-uri https://resource.champi40ns.eu/user_alice \\\n"
            + "    --user-name Alice \\\n"
            + "    --graph https://data.champi40ns.eu/joinery-product \\\n"
            + "    --odrl-folder src/main/resources/odrl_test/ \\\n"
            + "\n"
            + "ODRL folder arg aliases:\n"
            + "  --odrl-folder <path>\n"
            + "  --odrlFolder <path>\n"
            + "  --policy-folder <path>\n"
            + "\n"
            + "Note:\n"
            + "  The rewritten query is always based on:\n"
            + "    SELECT ?s ?p ?o WHERE { GRAPH <graph> { ?s ?p ?o } }\n";
    }

    private static String getOdrlFolderArg(Map<String, String> parsed) {
        if (parsed == null) {
            return "src/main/resources/odrl_test/";
        }

        String defaultFolder = "src/main/resources/odrl_test/";
        String v = parsed.get("odrl-folder");
        if (v == null || v.isBlank()) {
            v = parsed.get("odrlFolder");
        }
        if (v == null || v.isBlank()) {
            v = parsed.get("policy-folder");
        }
        return (v == null || v.isBlank()) ? defaultFolder : v;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        if (args == null || args.length == 0) {
            out.put("help", "true");
            return out;
        }

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a == null) {
                continue;
            }

            if ("--help".equals(a) || "-h".equals(a)) {
                out.put("help", "true");
                return out;
            }

            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected arg: " + a);
            }

            String key = a.substring(2);
            if (key.isBlank()) {
                throw new IllegalArgumentException("Invalid arg: " + a);
            }

            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for arg: " + a);
            }
            String value = args[++i];
            out.put(key, value);
        }

        return out;
    }

    private static String required(Map<String, String> parsed, String key) {
        String v = parsed.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: --" + key);
        }
        return v;
    }

}
