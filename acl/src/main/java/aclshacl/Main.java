package aclshacl;

import java.nio.file.Path;

import org.apache.jena.graph.Node;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.Syntax;

public class Main {
    public static void main(String[] args) {
        //HelperFunctions helper = new HelperFunctions();
        //CoreUtils helper = new CoreUtils();

        User user1 = new User("https://resource.champi40ns.eu/user_alice", "Alice");

        String queryString = "SELECT ?s ?p ?o WHERE { GRAPH <https://data.champi40ns.eu/joinery-product> { ?s ?p ?o }}";
        Query query = QueryFactory.create(queryString, Syntax.syntaxSPARQL_11);

        // get the first element of graphIris
        
        // retrieve graphIri - first element and store it in a variable
        Node graphIri = CoreUtils.collectGraphIris(query).iterator().next();
        //CoreUtils.collectGraphIris(query).forEach(graphIri -> System.out.println("Found graph IRI: " + graphIri));
        
        queryString = CoreUtils.normalizeQuery(query).toString();
        Query normalized = CoreUtils.rewriteQueryWithOdrlPolicies(queryString, Path.of("src/main/resources/odrl_test/"), graphIri, user1);
        
        if (normalized != null) {
            System.out.println("Normalized Query:");
            System.out.println(normalized);
        } else {
            System.out.println("Query could not be normalized (no GRAPH <...> or FROM)");
        }
    }
}
