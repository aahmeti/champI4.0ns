package aclshacl;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.Syntax;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.core.TriplePath;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.BindingFactory;
import org.apache.jena.sparql.expr.E_Equals;
import org.apache.jena.sparql.expr.E_GreaterThan;
import org.apache.jena.sparql.expr.E_GreaterThanOrEqual;
import org.apache.jena.sparql.expr.E_LessThan;
import org.apache.jena.sparql.expr.E_LessThanOrEqual;
import org.apache.jena.sparql.expr.E_LogicalAnd;
import org.apache.jena.sparql.expr.E_LogicalNot;
import org.apache.jena.sparql.expr.E_LogicalOr;
import org.apache.jena.sparql.expr.E_NotEquals;
import org.apache.jena.sparql.expr.E_NotExists;
import org.apache.jena.sparql.expr.E_NotOneOf;
import org.apache.jena.sparql.expr.E_OneOf;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprVar;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementData;
import org.apache.jena.sparql.syntax.ElementFilter;
import org.apache.jena.sparql.syntax.ElementGroup;
import org.apache.jena.sparql.syntax.ElementNamedGraph;
import org.apache.jena.sparql.syntax.ElementPathBlock;
import org.apache.jena.sparql.syntax.ElementTriplesBlock;
import org.apache.jena.sparql.syntax.ElementUnion;
import org.apache.jena.sparql.syntax.ElementVisitorBase;
import org.apache.jena.sparql.syntax.ElementWalker;
import org.apache.jena.sparql.syntax.syntaxtransform.ElementTransformCopyBase;
import org.apache.jena.sparql.syntax.syntaxtransform.ElementTransformer;
import org.apache.jena.vocabulary.RDF;

/**
 * Core utilities for:
 * - Loading RDF/Turtle into Jena {@link Model} instances.
 * - Loading and filtering ODRL policies (permissions/prohibitions) from a folder of .ttl files.
 * - Normalizing SPARQL queries to a consistent {@code GRAPH ?g { ... }} form.
 * - Rewriting SPARQL queries to enforce ODRL read policies by injecting:
 *   - Permission branches as {@code GRAPH <urn:acl> { ... }} patterns, typically combined using {@code UNION}.
 *   - Prohibitions as {@code FILTER NOT EXISTS { ... }} patterns (for refined prohibitions) and/or global predicate denylists.
 *
 * Notes on semantics used by the rewrite:
 * - Multiple permissions widen access, so they are combined using {@code UNION}.
 * - Multiple refinements on the same asset are AND'ed; refinements across different assets/policies for the same predicate are OR'ed.
 * - Predicate-only asset constraints are represented as a global {@code FILTER (?p IN (...))} when available.
 *   Refined permission constraints can be kept per-branch (via {@code VALUES}+filters) to preserve shape in output.
 */
public class CoreUtils {

    // Common variables used throughout the rewrite.
    // These are used when building SPARQL algebra/syntax elements and when emitting FILTER expressions.
    private static final Node varG = NodeFactory.createVariable("g");
    private static final Node varUser = NodeFactory.createVariable("user");

    // Historical/policy-side predicate variable names used when constructing VALUES joins.
    // NOTE: We keep these constants because per-branch refined permission output uses VALUES ?p_acl.
    private static final Node varPPolicy = NodeFactory.createVariable("p_acl");

    private static final String ODRL_NS = "http://www.w3.org/ns/odrl/2/";
    private static final String VCARD_NS = "http://www.w3.org/2006/vcard/ns#";

    // Prevent instantiation: this class is a namespace for static helpers.
    private CoreUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads an RDF model from a classpath resource, filesystem path, or URI.
     * 
     * @param path the path to the RDF resource
     * @return a Jena Model containing the RDF data
     * @throws IllegalArgumentException if path is null or blank
     * @throws org.apache.jena.riot.RiotException if RDF cannot be parsed
     */
    public static Model readRdfModel(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Path must not be null or blank");
        }

        String loc = path.trim();
        String resourceName = loc.startsWith("/") ? loc.substring(1) : loc;

        // Try classpath resource
        URL resourceUrl = CoreUtils.class.getClassLoader().getResource(resourceName);
        if (resourceUrl != null) {
            return RDFDataMgr.loadModel(resourceUrl.toString());
        }

        // Try filesystem
        Path filePath = Paths.get(loc);
        if (Files.exists(filePath)) {
            return RDFDataMgr.loadModel(filePath.toUri().toString());
        }

        // If above fails: treat as URI
        return RDFDataMgr.loadModel(loc);
    }

    /**
     * Reads ODRL policies from Turtle files in the specified folder, separating permissions and prohibitions.
     * 
     * @param odrlFolder the path to the folder containing ODRL policy files
     * @return an ODRLPolicyModels record containing separate models for permissions and prohibitions
     * @throws IllegalArgumentException if odrlFolder is null or not a directory
     * @throws RuntimeException if there is an error reading the files
     */
    public static ODRLPolicyModels readOdrlFolderPolicies(Path odrlFolder) {
        
        // Validate input
        if (odrlFolder == null) {
            throw new IllegalArgumentException("odrlFolder must not be null");
        }
        if (!Files.isDirectory(odrlFolder)) {
            throw new IllegalArgumentException("odrlFolder is not a directory: " + odrlFolder);
        }

        // Initialize empty models for permissions and prohibitions
        Model permissionsModel = ModelFactory.createDefaultModel();
        Model prohibitionsModel = ModelFactory.createDefaultModel();

        // Read all .ttl files in the folder and separate permissions and prohibitions
        try (Stream<Path> paths = Files.list(odrlFolder)) {
            paths.filter(p -> p.getFileName().toString().endsWith(".ttl"))
                 .forEach(p -> {
                     Model m = RDFDataMgr.loadModel(p.toString());
                     if (m.contains(null, ODRL.permission)) {
                         permissionsModel.add(extractRulesModel(m, ODRL.permission));
                     }
                     if (m.contains(null, ODRL.prohibition)) {
                         prohibitionsModel.add(extractRulesModel(m, ODRL.prohibition));
                     }
                 });
        } catch (Exception e) {
            throw new RuntimeException("Failed to read ODRL policies from folder: " + odrlFolder, e);
        }

        return new ODRLPolicyModels(permissionsModel, prohibitionsModel);
    }

    /**
     * Reads ODRL policies from Turtle files in the specified folder for a specific user, separating permissions and prohibitions.
     * Only includes policies where odrl:assignee matches the user's URI.
     * 
     * @param odrlFolder the path to the folder containing ODRL policy files
     * @param user the user for whom to filter policies
     * @return an ODRLPolicyModels record containing separate models for permissions and prohibitions matching the user
     * @throws IllegalArgumentException if odrlFolder is null, not a directory, or user is null
     * @throws RuntimeException if there is an error reading the files
     */
    public static ODRLPolicyModels readOdrlFolderPolicies(Path odrlFolder, Node graph, User user) {
        
        // Validate input
        if (odrlFolder == null) {
            throw new IllegalArgumentException("odrlFolder must not be null");
        }
        if (!Files.isDirectory(odrlFolder)) {
            throw new IllegalArgumentException("odrlFolder is not a directory: " + odrlFolder);
        }
        if (graph == null || !graph.isURI()) {
            throw new IllegalArgumentException("graph must be a URI node");
        }
        if (user == null) {
            throw new IllegalArgumentException("user must not be null");
        }

        // Initialize empty models for permissions and prohibitions
        Model permissionsModel = ModelFactory.createDefaultModel();
        Model prohibitionsModel = ModelFactory.createDefaultModel();

        String userUri = user.getUri();
        String graphUri = graph.getURI();

        // Read all .ttl files in the folder and select only matching permission/prohibition rules.
        try (Stream<Path> paths = Files.list(odrlFolder)) {
            paths.filter(p -> p.getFileName().toString().endsWith(".ttl"))
                 .forEach(p -> {
                     Model m = RDFDataMgr.loadModel(p.toString());
                     Resource userResource = m.createResource(userUri);
                     Resource graphResource = m.createResource(graphUri);

                     if (m.contains(null, ODRL.permission)) {
                         Model matchingPerms = extractMatchingRulesModel(m, ODRL.permission, graphResource, userResource);
                         if (!matchingPerms.isEmpty()) {
                             permissionsModel.add(matchingPerms);
                         }
                     }
                     if (m.contains(null, ODRL.prohibition)) {
                         Model matchingProhibs = extractMatchingRulesModel(m, ODRL.prohibition, graphResource, userResource);
                         if (!matchingProhibs.isEmpty()) {
                             prohibitionsModel.add(matchingProhibs);
                         }
                     }
                 });
        } catch (Exception e) {
            throw new RuntimeException("Failed to read ODRL policies from folder: " + odrlFolder, e);
        }

        return new ODRLPolicyModels(permissionsModel, prohibitionsModel);
    }

    /**
     * Pulls out just the relevant parts of a policy model for one rule type.
     * To simplify, think of this as making a smaller model that only contains permission rules 
     * or only prohibition rules.
     * @param m the original model containing one or more rules
     * @param ruleProp either ODRL.permission or ODRL.prohibition
     * @return a new model containing only the relevant rules and their associated target/assignee descriptions, but not the rest of the original model
     */
    private static Model extractRulesModel(Model m, Property ruleProp) {
        Model answer = ModelFactory.createDefaultModel();
        if (m == null || m.isEmpty() || ruleProp == null) {
            return answer;
        }
        for (Statement st : m.listStatements(null, ruleProp, (RDFNode) null).toList()) {
            RDFNode ruleNode = st.getObject();
            if (ruleNode == null || !ruleNode.isResource()) {
                continue;
            }
            Resource ruleRes = ruleNode.asResource();
            addRuleToModel(m, answer, ruleRes);
            // Keep the (policy -> rule) link so we can later build UNION branches (one per rule).
            answer.add(st);
        }
        return answer;
    }


    /**
     * Same as {@link #extractRulesModel(Model, Property)} but filters rules by target graph and assignee.
     *
     * A rule matches when:
     * - {@code odrl:assignee} equals the user, or is a PartyCollection containing the user.
     * - {@code odrl:target} equals the graph IRI, or is an Asset with {@code odrl:partOf} that graph.
     * @param m the original model containing one or more rules
     * @param ruleProp either ODRL.permission or ODRL.prohibition
     * @param graphResource the graph IRI to match against rule targets
     * @param userResource the user IRI to match against rule assignees
      * @return a new model containing only the relevant rules and their associated target/assignee descriptions, but not the rest of the original model
     */
    private static Model extractMatchingRulesModel(Model m, Property ruleProp, Resource graphResource, Resource userResource) {
        Model answer = ModelFactory.createDefaultModel();
        if (m == null || m.isEmpty() || ruleProp == null || graphResource == null || userResource == null) {
            return answer;
        }
        for (Statement st : m.listStatements(null, ruleProp, (RDFNode) null).toList()) {
            RDFNode ruleNode = st.getObject();
            if (ruleNode == null || !ruleNode.isResource()) {
                continue;
            }
            Resource ruleRes = ruleNode.asResource();
            if (!ruleAssigneeMatches(m, ruleRes, userResource)) {
                continue;
            }
            if (!ruleTargetMatches(m, ruleRes, graphResource)) {
                continue;
            }
            addRuleToModel(m, answer, ruleRes);
            answer.add(st);
        }
        return answer;
    }


    /**
     * Copies a single rule (and the supporting target/assignee descriptions) from {@code src} to {@code dst}.
     *
     * This method is intentionally conservative: it copies only the minimal subgraph needed for later rewrite and
     * refinement parsing, and avoids copying the entire policy model.
     * Concretely, this helper copies just the minimum closure that the rewriter depends on: The rule node’s own triples (e.g., odrl:target, odrl:assignee, odrl:action, …).
     * The odrl:target resource description (e.g., the odrl:Asset node with odrl:partOf, rdf:predicate, etc.).
     * Any refinement blank-node subgraph reachable from the target via odrl:refinement, so refinement parsing and later FILTER generation can work.
     * The odrl:assignee resource description (including copying blank-node closure if the assignee is anonymous), so PartyCollection-style membership patterns aren’t lost.
     * @param src the source model containing the original rule
     * @param dst the destination model to which the rule should be copied
     * @param ruleRes the root resource of the rule to copy (the object of a permission/prohibition triple)
     * @return nothing; the method modifies the dst model in-place
     */
    private static void addRuleToModel(Model src, Model dst, Resource ruleRes) {
        if (src == null || dst == null || ruleRes == null) {
            return;
        }

        // Copy direct rule statements (target/assignee/action/etc)
        src.listStatements(ruleRes, null, (RDFNode) null).forEachRemaining(dst::add);

        // Copy target resource statements (graph IRI or Asset)
        for (Statement targetSt : src.listStatements(ruleRes, ODRL.target, (RDFNode) null).toList()) {
            RDFNode t = targetSt.getObject();
            if (t != null && t.isResource()) {
                Resource targetRes = t.asResource();
                src.listStatements(targetRes, null, (RDFNode) null).forEachRemaining(dst::add);

                // Copy refinement blank-node closure so refinement parsing works.
                Property odrlRefinement = src.createProperty(ODRL_NS + "refinement");
                for (Statement rSt : src.listStatements(targetRes, odrlRefinement, (RDFNode) null).toList()) {
                    RDFNode refNode = rSt.getObject();
                    if (refNode != null && refNode.isAnon() && refNode.isResource()) {
                        copyBlankNodeClosure(src, dst, refNode.asResource());
                    }
                }
            }
        }

        // Copy assignee resource statements (user IRI or PartyCollection)
        for (Statement aSt : src.listStatements(ruleRes, ODRL.assignee, (RDFNode) null).toList()) {
            RDFNode a = aSt.getObject();
            if (a != null && a.isResource()) {
                Resource assigneeRes = a.asResource();
                src.listStatements(assigneeRes, null, (RDFNode) null).forEachRemaining(dst::add);
                if (assigneeRes.isAnon()) {
                    copyBlankNodeClosure(src, dst, assigneeRes);
                }
            }
        }
    }

    /**
     * Determines whether a rule applies to a given user. -> Alternatively, ASK query.
     * Matching logic:
     * - Direct: {@code rule odrl:assignee <user>}.
     * - Group: {@code rule odrl:assignee <partyCollection>} where
     *   {@code <partyCollection> a odrl:PartyCollection ; vcard:hasMember <user>}.
     * @param  m the model containing the rule and related descriptions
     * @param ruleRes the root resource of the rule to check (the object of a permission/prohibition triple)
     * @param userResource the resource representing the user to check
      * @return true if the rule applies to the user, false otherwise   
     */
    private static boolean ruleAssigneeMatches(Model m, Resource ruleRes, Resource userResource) {
        if (m == null || ruleRes == null || userResource == null) {
            return false;
        }
        if (m.contains(ruleRes, ODRL.assignee, userResource)) {
            return true;
        }

        Resource partyCollectionType = m.createResource(ODRL_NS + "PartyCollection");
        Property vcardHasMember = m.createProperty(VCARD_NS + "hasMember");

        // Otherwise check any assignee resources to see if they represent a PartyCollection containing the user.
        for (Statement st : m.listStatements(ruleRes, ODRL.assignee, (RDFNode) null).toList()) {
            RDFNode a = st.getObject();
            if (a == null || !a.isResource()) {
                continue;
            }
            Resource assigneeRes = a.asResource();
            if (m.contains(assigneeRes, RDF.type, partyCollectionType) && m.contains(assigneeRes, vcardHasMember, userResource)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Determines whether a rule's target matches a given dataset/graph. -> Alternatively, ASK query.
     *
     * Matching logic:
     * - Direct dataset target: {@code rule odrl:target <graph>}.
     * - Asset target: {@code rule odrl:target <asset>} where
     *   {@code <asset> a odrl:Asset ; odrl:partOf <graph>}.
     * @param m the model containing the rule and related descriptions
     * @param ruleRes the root resource of the rule to check (the object of a permission/prohibition triple)
     * @param graphResource the resource representing the dataset/graph to check
      * @return true if the rule applies to the graph, false otherwise
     */
    private static boolean ruleTargetMatches(Model m, Resource ruleRes, Resource graphResource) {
        if (m == null || ruleRes == null || graphResource == null) {
            return false;
        }
        if (m.contains(ruleRes, ODRL.target, graphResource)) {
            return true;
        }

        Resource assetType = m.createResource(ODRL_NS + "Asset");
        for (Statement st : m.listStatements(ruleRes, ODRL.target, (RDFNode) null).toList()) {
            RDFNode t = st.getObject();
            if (t == null || !t.isResource()) {
                continue;
            }
            Resource targetRes = t.asResource();
            if (m.contains(targetRes, RDF.type, assetType) && m.contains(targetRes, ODRL.partOf, graphResource)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Converts an ODRL model to a triple pattern with variables for target and assignee.
     * 
     * @param odrlModel the ODRL model
     * @return an ElementTriplesBlock containing the triple patterns, or null if the model is empty
     * @throws IllegalArgumentException if odrlModel is null
     */
    public static ElementTriplesBlock convertToTriplePattern(Model odrlModel) {
        // Default conversion used for callers that don't need PartyCollection rewriting.
        return convertToTriplePattern(odrlModel, false, varPPolicy, false);
    }

    /**
     * Converts an ODRL model to a triple pattern suitable for injection into the rewritten query.
     *
     * @param rewritePartyObjectToUserVar if true, rewrite {@code vcard:hasMember <user>} lookups to use {@code ?user}
     * @param policyPredicateVar variable node to use when emitting policy-side predicate triples (when not dropped)
     * @param dropAssetPredicateTriples if true, drop {@code rdf:predicate} triples from the output pattern because
     *                                 predicate constraints are enforced via filters instead
     * @return an ElementTriplesBlock containing the triple patterns, or null if the model is empty
      * @throws IllegalArgumentException if odrlModel is null
     */
    private static ElementTriplesBlock convertToTriplePattern(Model odrlModel, boolean rewritePartyObjectToUserVar, Node policyPredicateVar, boolean dropAssetPredicateTriples) {

        // Validate input
        if (odrlModel == null) {
            throw new IllegalArgumentException("odrlModel must not be null");
        }
        if (odrlModel.isEmpty()) {
            return null;
        }

        // Create triple patterns with variables for target and assignee
        Node odrlTarget = NodeFactory.createURI(ODRL.target.getURI());
        Node odrlAssignee = NodeFactory.createURI(ODRL.assignee.getURI());
        Node vcardHasMember = NodeFactory.createURI(VCARD_NS + "hasMember");

        // Structural predicates we don't want in the joinable pattern
        Node odrlPermissionPred = NodeFactory.createURI(ODRL.permission.getURI());
        Node odrlProhibitionPred = NodeFactory.createURI(ODRL.prohibition.getURI());
        Node rdfType = RDF.type.asNode();
        Node odrlSet = NodeFactory.createURI("http://www.w3.org/ns/odrl/2/Set");
        Node odrlPartyCollection = NodeFactory.createURI("http://www.w3.org/ns/odrl/2/PartyCollection");
        Node odrlAsset = NodeFactory.createURI("http://www.w3.org/ns/odrl/2/Asset");
        Node odrlPartOf = NodeFactory.createURI(ODRL.partOf.getURI());
        Node rdfPredicate = RDF.predicate.asNode();

        // Refinements are compiled into FILTERs; do not emit the refinement structure as triples.
        Node odrlRefinement = NodeFactory.createURI(ODRL_NS + "refinement");
        Node odrlLeftOperand = NodeFactory.createURI(ODRL_NS + "leftOperand");
        Node odrlOperator = NodeFactory.createURI(ODRL_NS + "operator");
        Node odrlRightOperand = NodeFactory.createURI(ODRL_NS + "rightOperand");
        Node odrlAnd = NodeFactory.createURI(ODRL_NS + "and");
        Node odrlOr = NodeFactory.createURI(ODRL_NS + "or");
        Node odrlXone = NodeFactory.createURI(ODRL_NS + "xone");

        // Compute refinement subgraph subjects so we can skip RDF list structure (rdf:first/rest)
        // and any nested bnodes entirely. Refinements are enforced via FILTERs.
        Set<Node> skipSubjects = collectRefinementSubgraphSubjects(odrlModel, odrlRefinement);

        // Create a new ElementTriplesBlock to hold the triple patterns
        ElementTriplesBlock graphPattern = new ElementTriplesBlock();

        // Iterate over all triples in the ODRL model and replace target and assignee with variables
        odrlModel.getGraph().find().forEachRemaining(t -> {
            Node s = t.getSubject();
            Node p = t.getPredicate();
            Node o = t.getObject();

            if (skipSubjects.contains(s)) {
                return;
            }

            // Drop structure like: <policy> odrl:permission _:b1 / <policy> odrl:prohibition _:b2
            if (p.equals(odrlPermissionPred) || p.equals(odrlProhibitionPred)) {
                return;
            }
            // Drop: <policy> a odrl:Set
            if (p.equals(rdfType) && o.equals(odrlSet)) {
                return;
            }

            // Drop refinement structure (already turned into FILTER constraints)
            if (p.equals(odrlRefinement) || p.equals(odrlLeftOperand) || p.equals(odrlOperator) || p.equals(odrlRightOperand)
				|| p.equals(odrlAnd) || p.equals(odrlOr) || p.equals(odrlXone)) {
                return;
            }

            if (p.equals(odrlTarget)) {
                // Target can be either a named graph (URI) or an Asset resource.
                // If it's an Asset, keep it and bind graph/predicate through the Asset description.
                if (!odrlModel.getGraph().contains(o, rdfType, odrlAsset)) {
                    o = varG;
                }
            } else if (p.equals(odrlAssignee)) {
                // If assignee points to a PartyCollection resource, keep it as-is and instead
                // rely on a separate (assignee vcard:hasMember ?user) triple to bind membership.
                if (!odrlModel.getGraph().contains(o, rdfType, odrlPartyCollection)) {
                    o = varUser;
                }
            } else if (rewritePartyObjectToUserVar && p.equals(vcardHasMember)) {
                // Avoid enumerating all parties; represent membership via a single ?user variable.
                o = varUser;
            } else if (p.equals(odrlPartOf) && odrlModel.getGraph().contains(s, rdfType, odrlAsset)) {
                // Asset applies to a specific dataset/graph; join it to ?g.
                o = varG;
            } else if (p.equals(rdfPredicate) && odrlModel.getGraph().contains(s, rdfType, odrlAsset)) {
                if (dropAssetPredicateTriples) {
                    return;
                }
                // Asset constrains which predicate/property is allowed.
                // Bind to a dedicated variable and later join it to the query predicate using VALUES+FILTER.
                if (policyPredicateVar != null) {
                    o = policyPredicateVar;
                }
            }

            graphPattern.addTriple(Triple.create(s, p, o));
        });

        return graphPattern;
    }

    /**
     * Performs a breadth-first traversal starting from the odrl:refinement nodes and collects all subjects in the reachable subgraph.
     * This allows us to skip emitting any triples from the refinement structure when converting the ODRL model to a triple pattern, since refinements are enforced via FILTERs rather than graph patterns.
     * @param odrlModel the ODRL model to traverse
     * @param odrlRefinement  the odrl:refinement property node used to find the starting points of the traversal
     * @return a set of nodes representing the subjects in the refinement subgraph that should be skipped when converting to triple patterns
     */
    private static Set<Node> collectRefinementSubgraphSubjects(Model odrlModel, Node odrlRefinement) {
        Set<Node> skipSubjects = new HashSet<>();
        if (odrlModel == null || odrlModel.isEmpty()) {
            return skipSubjects;
        }
        if (odrlRefinement == null) {
            return skipSubjects;
        }

        Deque<Node> queue = new ArrayDeque<>();

        // Start from each object of (asset odrl:refinement ?refNode)
        odrlModel.getGraph().find(null, odrlRefinement, null).forEachRemaining(t -> {
            Node refNode = t.getObject();
            if (refNode != null && refNode.isBlank()) {
                queue.add(refNode);
            }
        });

        while (!queue.isEmpty()) {
            Node current = queue.removeFirst();
            if (!skipSubjects.add(current)) {
                continue;
            }
            odrlModel.getGraph().find(current, null, null).forEachRemaining(t -> {
                Node obj = t.getObject();
                if (obj != null && obj.isBlank() && !skipSubjects.contains(obj)) {
                    queue.addLast(obj);
                }
            });
        }

        return skipSubjects;
    }

    /**
     * Normalizes a SPARQL query by rewriting GRAPH clauses to use a variable and adding a filter to restrict to a single graph IRI.
     * @param query the SPARQL query to normalize
     * @return a normalized SPARQL query, or null if the query is null or has no query pattern  
     */
    public static Query normalizeQuery(Query query) {
        if (query == null || query.getQueryPattern() == null) {
            return null;
        }

        Set<Node> graphIris = collectGraphIris(query);
        
        if (graphIris.isEmpty() || graphIris.size() > 1) {
            return null;
        }

        Element transformedPattern = transformGraphClauses(query);
        Element patternWithFilter = addGraphFilter(transformedPattern, graphIris.iterator().next());
        
        return buildNormalizedQuery(query, patternWithFilter);
    }

    /**
     * Rewrites a SPARQL query by incorporating ODRL policies from the specified folder.
     *
     * High-level steps:
     * 1. Load ODRL permission/prohibition policies from {@code odrlFolder} (optionally filtered by {@code graphIri} and {@code user}).
     * 2. Inject permission patterns into the query as {@code GRAPH <urn:acl> { ... }} blocks, combined using {@code UNION}
     *    (permissions widen access).
     * 3. If policies include predicate-level constraints (via {@code rdf:predicate} or refinements), add a global allow-list
     *    {@code FILTER (?p IN (...))} where possible.
     * 4. Inject prohibitions as {@code FILTER NOT EXISTS { ... }} for refined prohibitions and/or emit a global
     *    {@code FILTER (?p NOT IN (...))} for unconditional predicate denylists.
     * 5. Optionally add a {@code ?user = <userIRI>} filter to bind the query-side user variable.
     *
     * @param originalQuery the original SPARQL query string
     * @param odrlFolder the folder containing ODRL policy Turtle files
     * @param graphIri the dataset/graph IRI being queried (used to filter which rules apply)
     * @param user the user to enforce access for (nullable)
     * @return the rewritten SPARQL query with ODRL constraints incorporated
     */
    public static Query rewriteQueryWithOdrlPolicies(String originalQuery, Path odrlFolder, Node graphIri, User user) {
        if (originalQuery == null || originalQuery.trim().isEmpty()) {
            throw new IllegalArgumentException("originalQuery must not be null/blank");
        }
        if (odrlFolder == null) {
            throw new IllegalArgumentException("odrlFolder must not be null");
        }

        // Load policies from disk. If a user is provided, we filter to only rules that apply to that user+graph.
        ODRLPolicyModels odrlModels;
        if (user != null) {
            odrlModels = readOdrlFolderPolicies(odrlFolder, graphIri, user);
        } else {
            odrlModels = readOdrlFolderPolicies(odrlFolder);
        }
        
        // Rewrite the query with ODRL policies.
        // When user is provided, prefer representing PartyCollection membership via a single ?user variable.
        Query rewritten = rewriteQueryWithOdrlPolicies(originalQuery, odrlModels, user != null);

        // Keep query output stable: avoid emitting duplicate triple patterns due to policy graph composition.
        rewritten = removeDuplicateTriplePatterns(rewritten);
        if (rewritten != null && rewritten.getQueryPattern() != null && !rewritten.equals(emptyQuery()) ){
            if (user != null) {
                rewritten.setQueryPattern(addUserFilter(rewritten.getQueryPattern(), user));
            }
        }
        return rewritten;
    }

    
    private static final class PredicateConstraints {
        private final Set<Node> predicates;
        private final java.util.Map<Node, Expr> refinementByPredicate;
        

        private PredicateConstraints(Set<Node> predicates, java.util.Map<Node, Expr> refinementByPredicate) {
            this.predicates = predicates;
            this.refinementByPredicate = refinementByPredicate;
        }

        boolean isEmpty() {
            return predicates.isEmpty();
        }
    }

    /** Summarises the predicate allow-list across all permission branches. */
    private static final class PermissionPredicateSummary {
        /** Every predicate allowed by any permission branch (refined or not). */
        private final Set<Node> allowedPredicates;

        private PermissionPredicateSummary(Set<Node> allowedPredicates) {
            this.allowedPredicates = allowedPredicates;
        }
    }

    /**
     * Collects all predicates mentioned in the ODRL model as rdf:predicate constraints on Assets, along with any associated refinements.
     * @param m the ODRL model to analyze, which may contain multiple policies/permissions with different predicate constraints
     * @return a PredicateConstraints object summarizing the predicates and their refinements
     */
    private static PredicateConstraints collectAssetPredicateConstraints(Model m) {
        Set<Node> predicates = new LinkedHashSet<>();
        java.util.Map<Node, Expr> refinementByPredicate = new java.util.LinkedHashMap<>();
        collectAssetPredicateConstraintsFromModel(m, predicates, refinementByPredicate);
        return new PredicateConstraints(predicates, refinementByPredicate);
    }

    private static List<Model> splitPermissionsIntoModels(Model permissionsModel) {
        List<Model> answer = new ArrayList<>();
        if (permissionsModel == null || permissionsModel.isEmpty()) {
            return answer;
        }

        // Each (policy odrl:permission _:bX) defines a separate permission rule.
        for (Statement st : permissionsModel.listStatements(null, ODRL.permission, (RDFNode) null).toList()) {
            RDFNode permNode = st.getObject();
            if (permNode == null || !permNode.isResource()) {
                continue;
            }

            Resource permRes = permNode.asResource();
            Model sub = ModelFactory.createDefaultModel();

            // Copy the permission node's direct statements (target/assignee/action/...)
            permissionsModel.listStatements(permRes, null, (RDFNode) null).forEachRemaining(sub::add);

            // Copy target resource description (e.g., Asset with partOf/refinement/rdf:predicate)
            Statement targetSt = permRes.getProperty(ODRL.target);
            if (targetSt != null && targetSt.getObject() != null && targetSt.getObject().isResource()) {
                Resource targetRes = targetSt.getObject().asResource();
                permissionsModel.listStatements(targetRes, null, (RDFNode) null).forEachRemaining(sub::add);

                // Copy refinement blank-node subgraph closure so refinement parsing works.
                Property odrlRefinement = permissionsModel.createProperty(ODRL_NS + "refinement");
                for (Statement rSt : permissionsModel.listStatements(targetRes, odrlRefinement, (RDFNode) null).toList()) {
                    RDFNode refNode = rSt.getObject();
                    if (refNode != null && refNode.isAnon()) {
                        copyBlankNodeClosure(permissionsModel, sub, refNode.asResource());
                    }
                }
            }

            // Copy assignee resource description (e.g., PartyCollection membership)
            Statement assigneeSt = permRes.getProperty(ODRL.assignee);
            if (assigneeSt != null && assigneeSt.getObject() != null && assigneeSt.getObject().isResource()) {
                Resource assigneeRes = assigneeSt.getObject().asResource();
                permissionsModel.listStatements(assigneeRes, null, (RDFNode) null).forEachRemaining(sub::add);
            }

            answer.add(sub);
        }

        return answer;
    }

    /**
     * Performs a breadth-first traversal to copy all statements reachable from the start resource via blank nodes.
     * @param src The source model from which to copy statements.
     * @param dst The destination model to which statements are copied.
     * @param start The starting resource for the traversal.    
     */
    private static void copyBlankNodeClosure(Model src, Model dst, Resource start) {
        if (src == null || dst == null || start == null || !start.isAnon()) {
            return;
        }
        Deque<Resource> queue = new ArrayDeque<>();
        Set<Resource> seen = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            Resource cur = queue.removeFirst();
            if (!seen.add(cur)) {
                continue;
            }
            for (Statement st : src.listStatements(cur, null, (RDFNode) null).toList()) {
                dst.add(st);
                RDFNode obj = st.getObject();
                if (obj != null && obj.isAnon() && obj.isResource()) {
                    queue.addLast(obj.asResource());
                }
            }
        }
    }

    /** 
     * Helper for collectAssetPredicateConstraints that walks the model and collects predicates and refinements, accumulating into the provided output parameters.
     * @param m the ODRL model to analyze
     * @param predicatesOut a set to which all discovered predicate IRIs will be added
     * @param refinementByPredicateOut a map to which any discovered refinements will be added, keyed by the predicate IRI they constrain
     */
    private static void collectAssetPredicateConstraintsFromModel(Model m, Set<Node> predicatesOut, java.util.Map<Node, Expr> refinementByPredicateOut) {
        if (m == null || m.isEmpty()) {
            return;
        }

        Resource odrlAsset = m.createResource(ODRL_NS + "Asset");
        Property odrlRefinement = m.createProperty(ODRL_NS + "refinement");
        Property odrlLeftOperand = m.createProperty(ODRL_NS + "leftOperand");
        Property odrlOperator = m.createProperty(ODRL_NS + "operator");
        Property odrlRightOperand = m.createProperty(ODRL_NS + "rightOperand");
        Property odrlAnd = m.createProperty(ODRL_NS + "and");
        Property odrlOr = m.createProperty(ODRL_NS + "or");
        Property odrlXone = m.createProperty(ODRL_NS + "xone");

        // Walk all selected policy targets; if the target is an Asset, collect rdf:predicate and/or refinement constraints.
        // Important: refinements from different assets/policies for the same predicate must be OR'd (multiple permissions widen access),
        // while multiple refinements on the same asset are AND'd (single asset's constraints must all hold).
        for (Statement st : m.listStatements(null, ODRL.target, (RDFNode) null).toList()) {
            RDFNode targetNode = st.getObject();
            if (targetNode == null || !targetNode.isResource()) {
                continue;
            }
            Resource asset = targetNode.asResource();
            if (!m.contains(asset, RDF.type, odrlAsset)) {
                continue;
            }

            java.util.Map<Node, Expr> refinementsForThisAsset = new java.util.HashMap<>();

            // Case 1: explicit rdf:predicate champ-onto:prop_1
            for (Statement pSt : m.listStatements(asset, RDF.predicate, (RDFNode) null).toList()) {
                RDFNode pred = pSt.getObject();
                if (pred != null && pred.isURIResource()) {
                    predicatesOut.add(pred.asNode());
                }
            }

            // Case 2: odrl:refinement
            //   - atomic: [ leftOperand <prop> ; operator <op> ; rightOperand <lit> ]
			//   - logical list: [ odrl:and ( [..] [..] ) ] (also odrl:or / odrl:xone)
            for (Statement rSt : m.listStatements(asset, odrlRefinement, (RDFNode) null).toList()) {
                RDFNode refinementNode = rSt.getObject();
                if (refinementNode == null || !refinementNode.isResource()) {
                    continue;
                }

                Resource refinement = refinementNode.asResource();

                boolean handledLogical = false;

                Statement andSt = refinement.getProperty(odrlAnd);
                Statement orSt = refinement.getProperty(odrlOr);
				Statement xoneSt = refinement.getProperty(odrlXone);

				if (andSt != null || orSt != null || xoneSt != null) {
                    Property logicalProp = andSt != null ? odrlAnd : (orSt != null ? odrlOr : odrlXone);
                    RDFNode listNode = andSt != null ? andSt.getObject() : (orSt != null ? orSt.getObject() : xoneSt.getObject());

                    RDFList list = asRdfList(listNode);
                    if (list != null) {
                        Map<Node, List<Expr>> exprsByPredicate = new HashMap<>();
                        for (RDFNode item : list.iterator().toList()) {
                            if (item == null || !item.isResource()) {
                                continue;
                            }
                            AtomicRefinement ar = parseAtomicRefinement(item.asResource(), odrlLeftOperand, odrlOperator, odrlRightOperand);
                            if (ar == null) {
                                continue;
                            }
                            predicatesOut.add(ar.predicateIri);
                            exprsByPredicate.computeIfAbsent(ar.predicateIri, k -> new ArrayList<>()).add(ar.exprOnO);
                        }

                        for (Map.Entry<Node, List<Expr>> e : exprsByPredicate.entrySet()) {
                            Node predicateIri = e.getKey();
                            Expr combined = combineExprs(logicalProp, e.getValue());
                            if (combined == null) {
                                continue;
                            }
                            Expr existing = refinementsForThisAsset.get(predicateIri);
                            refinementsForThisAsset.put(predicateIri, existing == null ? combined : new E_LogicalAnd(existing, combined));
                        }

                        handledLogical = true;
                    }
                }

                if (handledLogical) {
                    continue;
                }

                // Fallback: atomic refinement directly on the refinement bnode
                AtomicRefinement ar = parseAtomicRefinement(refinement, odrlLeftOperand, odrlOperator, odrlRightOperand);
                if (ar == null) {
                    continue;
                }

                predicatesOut.add(ar.predicateIri);
                Expr existing = refinementsForThisAsset.get(ar.predicateIri);
                refinementsForThisAsset.put(ar.predicateIri, existing == null ? ar.exprOnO : new E_LogicalAnd(existing, ar.exprOnO));
            }

            // Merge this asset's refinements into global constraints: OR across assets/policies for the same predicate.
            for (java.util.Map.Entry<Node, Expr> e : refinementsForThisAsset.entrySet()) {
                Node predicateIri = e.getKey();
                Expr expr = e.getValue();
                if (predicateIri == null || !predicateIri.isURI() || expr == null) {
                    continue;
                }
                Expr existing = refinementByPredicateOut.get(predicateIri);
                refinementByPredicateOut.put(predicateIri, existing == null ? expr : new E_LogicalOr(existing, expr));
            }
        }
    }

    /**
     * Helper to parse an atomic refinement of the form:
     * [ odrl:leftOperand <predicate> ; odrl:operator <op> ; odrl:rightOperand <value> ]
     * @param node  the RDF node representing the refinement (should be a blank node with the above properties)
     * @return  an AtomicRefinement object containing the predicate IRI and the corresponding SPARQL expression, or null if parsing fails
     */
    private static RDFList asRdfList(RDFNode node) {
        if (node == null) {
            return null;
        }
        try {
            if (node.canAs(RDFList.class)) {
                return node.as(RDFList.class);
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /**
     * Helper class representing an atomic refinement, which consists of a predicate IRI and the corresponding SPARQL expression.
     */
    private static final class AtomicRefinement {
        private final Node predicateIri;
        private final Expr exprOnO;

        private AtomicRefinement(Node predicateIri, Expr exprOnO) {
            this.predicateIri = predicateIri;
            this.exprOnO = exprOnO;
        }
    }

    /** 
     * Parses an atomic refinement from a blank node with the expected structure.
     * @param constraintNode the RDF resource representing the refinement constraint
     * @param odrlLeftOperand the Property representing odrl:leftOperand
     * @param odrlOperator the Property representing odrl:operator
     * @param odrlRightOperand the Property representing odrl:rightOperand
     * @return an AtomicRefinement object if parsing is successful, or null if parsing fails due to missing properties or invalid structure
     */
    private static AtomicRefinement parseAtomicRefinement(Resource constraintNode,
        Property odrlLeftOperand,
        Property odrlOperator,
        Property odrlRightOperand) {

        if (constraintNode == null) {
            return null;
        }

        RDFNode left = constraintNode.getProperty(odrlLeftOperand) != null ? constraintNode.getProperty(odrlLeftOperand).getObject() : null;
        RDFNode op = constraintNode.getProperty(odrlOperator) != null ? constraintNode.getProperty(odrlOperator).getObject() : null;
        RDFNode right = constraintNode.getProperty(odrlRightOperand) != null ? constraintNode.getProperty(odrlRightOperand).getObject() : null;

        if (left == null || !left.isURIResource() || op == null || !op.isURIResource() || right == null) {
            return null;
        }

        Node predicateIri = left.asNode();
        Expr refinementExpr = buildOdrlRefinementExpr(op.asNode(), right.asNode());
        if (refinementExpr == null) {
            return null;
        }

        return new AtomicRefinement(predicateIri, refinementExpr);
    }

    /**
     * Combines multiple expressions for the same predicate using the specified logical operator (AND/OR/XONE).
     * @param logicalProp the RDF property representing the logical operator (e.g., odrl:and, odrl:or, odrl:xone) that indicates how to combine the expressions
     * @param exprs the list of expressions to combine
     * @return a single expression representing the combination of the input expressions according to the logical operator, or null if the input list is null/empty or if the logical operator is unrecognized
     */
    private static Expr combineExprs(Property logicalProp, List<Expr> exprs) {
        if (exprs == null || exprs.isEmpty()) {
            return null;
        }
        if (exprs.size() == 1) {
            return exprs.get(0);
        }

        String uri = logicalProp != null ? logicalProp.getURI() : null;
        if (ODRL_NS.concat("and").equals(uri)) {
            Expr out = null;
            for (Expr e : exprs) {
                out = (out == null) ? e : new E_LogicalAnd(out, e);
            }
            return out;
        }
        if (ODRL_NS.concat("or").equals(uri)) {
            Expr out = null;
            for (Expr e : exprs) {
                out = (out == null) ? e : new E_LogicalOr(out, e);
            }
            return out;
        }
		if (ODRL_NS.concat("xone").equals(uri)) {
            return xorExactlyOne(exprs);
        }

        // Unknown logical operator; default to AND as the safest restriction.
        Expr out = null;
        for (Expr e : exprs) {
            out = (out == null) ? e : new E_LogicalAnd(out, e);
        }
        return out;
    }

    // N-ary XOR: exactly one expression must be true.
    private static Expr xorExactlyOne(List<Expr> exprs) {
        if (exprs == null || exprs.isEmpty()) {
            return null;
        }
        if (exprs.size() == 1) {
            return exprs.get(0);
        }

        Expr disj = null;
        for (int i = 0; i < exprs.size(); i++) {
            Expr term = exprs.get(i);
            if (term == null) {
                continue;
            }
            Expr conj = term;
            for (int j = 0; j < exprs.size(); j++) {
                if (j == i) {
                    continue;
                }
                Expr other = exprs.get(j);
                if (other == null) {
                    continue;
                }
                conj = new E_LogicalAnd(conj, new E_LogicalNot(other));
            }
            disj = (disj == null) ? conj : new E_LogicalOr(disj, conj);
        }

        return disj;
    }

    /**
     * Builds a SPARQL expression representing the ODRL refinement constraint based on the operator IRI and right operand.
     * @param operatorIri the RDF node representing the ODRL operator (e.g., odrl:lteq, odrl:gteq, odrl:lt, odrl:gt, odrl:eq, odrl:neq)
     * @param rightOperand the RDF node representing the right operand of the refinement constraint (e
     * .g., a literal value that the query variable is compared against)
     * @return a SPARQL expression representing the refinement constraint (e.g., ?o <= rightOperand for odrl:lteq), or null if the operator is unrecognized or if inputs are invalid
     */
    private static Expr buildOdrlRefinementExpr(Node operatorIri, Node rightOperand) {
        if (operatorIri == null || !operatorIri.isURI() || rightOperand == null) {
            return null;
        }

        String op = operatorIri.getURI();
        ExprVar left = new ExprVar("o");
        NodeValue right = NodeValue.makeNode(rightOperand);

        // Map common ODRL operator IRIs to SPARQL comparison expressions.
        if (ODRL_NS.concat("lteq").equals(op)) {
            return new E_LessThanOrEqual(left, right);
        }
        if (ODRL_NS.concat("gteq").equals(op)) {
            return new E_GreaterThanOrEqual(left, right);
        }
        if (ODRL_NS.concat("lt").equals(op)) {
            return new E_LessThan(left, right);
        }
        if (ODRL_NS.concat("gt").equals(op)) {
            return new E_GreaterThan(left, right);
        }
        if (ODRL_NS.concat("eq").equals(op)) {
            return new E_Equals(left, right);
        }
        if (ODRL_NS.concat("neq").equals(op)) {
            return new E_NotEquals(left, right);
        }

        return null;
    }

    /**
     *
     * This method is responsible for taking an already-normalized SPARQL query string and injecting:
     * - Permission branches (as {@code UNION} of {@code GRAPH <urn:acl> { ... }} blocks).
     * - Optional global predicate allow-list {@code FILTER (?p IN (...))} when we can summarize predicates from policy targets.
     * - Prohibitions as {@code FILTER NOT EXISTS { ... }} for refined prohibitions.
     * - Optional global predicate deny-list {@code FILTER (?p NOT IN (...))} for unconditional predicate denials.
     */
    private static Query rewriteQueryWithOdrlPolicies(String originalQuery, ODRLPolicyModels odrlPolicy, boolean rewritePartyObjectToUserVar) {
        if (originalQuery == null || originalQuery.trim().isEmpty()) {
            throw new IllegalArgumentException("originalQuery must not be null/blank");
        }
        if (odrlPolicy == null) {
            throw new IllegalArgumentException("odrlPolicy must not be null");
        }

        Query query = QueryFactory.create(originalQuery, Syntax.syntaxSPARQL_11);
        Element pattern = query.getQueryPattern();
        if (pattern == null) {
            return query;
        }

        ElementGroup group;
        if (pattern instanceof ElementGroup g) {
            group = g;
        } else {
            group = new ElementGroup();
            group.addElement(pattern);
            query.setQueryPattern(group);
        }

        PermissionPredicateSummary permSummary = summarizePermissionPredicates(odrlPolicy.getPermissions());
        // Summarize predicate-level permissions (for global IN filter) and unconditional denials (for global NOT IN).
        // Refined prohibitions are handled by NOT EXISTS; refined permissions may be kept per-branch.
        Set<Node> unconditionallyDenied = collectUnconditionallyDeniedPredicates(odrlPolicy.getProhibitions());

        // Build the permission and prohibition elements that will be injected into the query.
        // Permissions are UNION'ed; prohibitions are later wrapped under FILTER NOT EXISTS.
        Element permissionsElement = buildPermissionsElement(odrlPolicy.getPermissions(), rewritePartyObjectToUserVar);
        Element prohibitionsElement = buildProhibitionsElement(odrlPolicy.getProhibitions(), rewritePartyObjectToUserVar);

        // No permissions or prohibitions means no access, so we can return an empty query that matches nothing
        // No permissions and no prohibitions means the user has no access.
        // We return an empty query that matches nothing (caller may treat this as "no results").
        if (permissionsElement == null && prohibitionsElement == null && unconditionallyDenied == null) {
            return emptyQuery();
        }

        if (permissionsElement != null) {
            group.addElement(permissionsElement);
        }

        // Global allow-list for predicates.
        // This is emitted only when we can collect a stable set of allowed predicate IRIs from policy targets.
        if (permSummary != null) {
			group.addElement(new ElementFilter(buildInExpr("p", permSummary.allowedPredicates, false)));
		}

        // Refined prohibitions are enforced as: FILTER NOT EXISTS { ... }.
        // This is correlated by ?p (and potentially constrained by ?o refinement filters inside the NOT EXISTS group).
        if (prohibitionsElement != null) {
			// FILTER NOT EXISTS { ... }
			ElementGroup notExistsGroup = new ElementGroup();
			notExistsGroup.addElement(prohibitionsElement);
			group.addElement(new ElementFilter(new E_NotExists(notExistsGroup)));
        }

        // Global deny-list for unconditional predicate prohibitions.
        if (unconditionallyDenied != null) {
            group.addElement(new ElementFilter(buildInExpr("p", unconditionallyDenied, true)));
        }

        return query;
    }

    /**
     * Builds the permission element injected into the query.
     *
     * Each permission rule becomes one UNION branch. Branches are wrapped in {@code GRAPH <urn:acl> { ... }}.
     *
     * For refined permission branches, we keep the refinement constraint inside the branch (via
     * {@code VALUES}+filters) so the output query clearly shows which branch carries the refinement.
     */
    private static Element buildPermissionsElement(Model permissionsModel, boolean rewritePartyObjectToUserVar) {
        if (permissionsModel == null || permissionsModel.isEmpty()) {
            return null;
        }

        Node aclGraph = NodeFactory.createURI("urn:acl");
        List<Model> branches = splitPermissionsIntoModels(permissionsModel);

        List<Element> branchElements = new ArrayList<>();
        for (Model branchModel : branches) {
			ElementTriplesBlock branchTriples = convertToTriplePattern(branchModel, rewritePartyObjectToUserVar, null, true);
            if (branchTriples == null) {
                continue;
            }

            ElementGroup branchGroup = new ElementGroup();
            branchGroup.addElement(new ElementNamedGraph(aclGraph, branchTriples));

            // For branches with refinements, keep per-branch VALUES + join + refinement filters.
            PredicateConstraints constraints = collectAssetPredicateConstraints(branchModel);
            if (!constraints.isEmpty() && constraints.refinementByPredicate != null && !constraints.refinementByPredicate.isEmpty()) {
                addPredicateValuesJoinAndRefinementFilters(branchGroup, constraints, "p_acl");
            }

            branchElements.add(branchGroup);
        }

        if (branchElements.isEmpty()) {
            return null;
        }
        if (branchElements.size() == 1) {
            return branchElements.get(0);
        }

        ElementUnion union = new ElementUnion();
        for (Element el : branchElements) {
            union.addElement(el);
        }
        return union;
    }

    /**
     * Always collects every predicate allowed by any permission branch.
     * This is used to emit the global allow-list filter: {@code FILTER (?p IN (...))}.
     */
    private static PermissionPredicateSummary summarizePermissionPredicates(Model permissionsModel) {
        if (permissionsModel == null || permissionsModel.isEmpty()) {
            return null;
        }
        List<Model> branches = splitPermissionsIntoModels(permissionsModel);
        if (branches.isEmpty()) {
            return null;
        }

        Set<Node> allAllowed = new LinkedHashSet<>();

        for (Model branchModel : branches) {
            PredicateConstraints c = collectAssetPredicateConstraints(branchModel);
            if (c == null || c.predicates == null || c.predicates.isEmpty()) {
                continue; // Skip branches with no predicate constraints (e.g. dataset-level permissions).
            }
            allAllowed.addAll(c.predicates);
        }

        if (allAllowed.isEmpty()) {
            return null;
        }

        return new PermissionPredicateSummary(allAllowed);
    }

    /**
     * Collects predicates that are unconditionally denied (no refinement).
     * Branches with refinements are left for NOT EXISTS handling.
     */
    private static Set<Node> collectUnconditionallyDeniedPredicates(Model prohibitionsModel) {
        if (prohibitionsModel == null || prohibitionsModel.isEmpty()) {
            return null;
        }
        List<Model> branches = splitProhibitionsIntoModels(prohibitionsModel);
        if (branches.isEmpty()) {
            return null;
        }

        Set<Node> denied = new LinkedHashSet<>();
        for (Model branchModel : branches) {
            PredicateConstraints c = collectAssetPredicateConstraints(branchModel);
            if (c == null || c.predicates == null || c.predicates.isEmpty()) {
                continue;
            }
            // Only collect predicates from branches without refinements (unconditional denials).
            if (c.refinementByPredicate != null && !c.refinementByPredicate.isEmpty()) {
                continue;
            }
            denied.addAll(c.predicates);
        }

        return denied.isEmpty() ? null : denied;
    }

    /**
     * Helper to build a SPARQL IN or NOT IN expression for a variable against a set of IRIs.
     * @param varName   the name of the SPARQL variable (without '?') to compare (e.g., "p")
     * @param iris the set of IRIs to include in the IN/NOT IN list
     * @param negate if true, builds a NOT IN expression; if false, builds an IN expression
     * @return a SPARQL expression representing either "?varName IN (iri1, iri2, ...)" or "?varName NOT IN (iri1, iri2, ...)", or null if inputs are invalid
     */
    private static Expr buildInExpr(String varName, Set<Node> iris, boolean negate) {
        if (varName == null || varName.isBlank() || iris == null || iris.isEmpty()) {
            return null;
        }
        org.apache.jena.sparql.expr.ExprList list = new org.apache.jena.sparql.expr.ExprList();
        for (Node n : iris) {
            if (n != null && n.isURI()) {
                list.add(NodeValue.makeNode(n));
            }
        }
        ExprVar v = new ExprVar(varName);
        return negate ? new E_NotOneOf(v, list) : new E_OneOf(v, list);
    }

    /**
     * Builds the prohibition element used under {@code FILTER NOT EXISTS { ... }}.
     *
     * Only refined prohibitions are expressed with NOT EXISTS (because they need to correlate to query bindings).
     * Unconditional predicate denials are emitted as a global {@code FILTER (?p NOT IN (...))} elsewhere.
     */
    private static Element buildProhibitionsElement(Model prohibitionsModel, boolean rewritePartyObjectToUserVar) {
        if (prohibitionsModel == null || prohibitionsModel.isEmpty()) {
            return null;
        }

        Node aclGraph = NodeFactory.createURI("urn:acl");
        List<Model> branches = splitProhibitionsIntoModels(prohibitionsModel);

        // Only branches with refinements need NOT EXISTS (unconditional denials are handled by global NOT IN).
        List<Element> branchElements = new ArrayList<>();
        for (Model branchModel : branches) {
            PredicateConstraints constraints = collectAssetPredicateConstraints(branchModel);
            if (constraints == null || constraints.isEmpty()) {
                continue;
            }
            // Skip branches without refinements — they are handled by global FILTER (?p NOT IN (...)).
            if (constraints.refinementByPredicate == null || constraints.refinementByPredicate.isEmpty()) {
                continue;
            }

            ElementTriplesBlock branchTriples = convertToTriplePattern(branchModel, rewritePartyObjectToUserVar, null, true);
            if (branchTriples == null) {
                continue;
            }

            ElementGroup branchGroup = new ElementGroup();
            branchGroup.addElement(new ElementNamedGraph(aclGraph, branchTriples));

            // For refined prohibitions, correlate ?p and apply refinement on ?o directly.
            Expr matchExpr = buildRefinedPredicateMatchExpr(constraints);
            if (matchExpr != null) {
                branchGroup.addElement(new ElementFilter(matchExpr));
            }

            branchElements.add(branchGroup);
        }

        if (branchElements.isEmpty()) {
            return null;
        }
        if (branchElements.size() == 1) {
            return branchElements.get(0);
        }

        ElementUnion union = new ElementUnion();
        for (Element el : branchElements) {
            union.addElement(el);
        }
        return union;
    }

    /**
     * Builds an expression that correlates ?p to specific IRIs and applies refinement conditions on ?o.
     * E.g.: FILTER ( (?p = <iri1> && ?o <= 50) || (?p = <iri2> && ?o >= 10) )
     */
    private static Expr buildRefinedPredicateMatchExpr(PredicateConstraints constraints) {
        if (constraints == null || constraints.isEmpty()) {
            return null;
        }
        Expr disj = null;
        for (Node iri : constraints.predicates) {
            if (iri == null || !iri.isURI()) {
                continue;
            }
            Expr predEq = new E_Equals(new ExprVar("p"), NodeValue.makeNode(iri));
            Expr refinement = (constraints.refinementByPredicate != null) ? constraints.refinementByPredicate.get(iri) : null;
            Expr branch = (refinement == null) ? predEq : new E_LogicalAnd(predEq, refinement);
            disj = (disj == null) ? branch : new E_LogicalOr(disj, branch);
        }
        return disj;
    }

    /**
     * Splits the prohibitions model into separate models for each prohibition rule, similar to how permissions are split.
     * @param prohibitionsModel the input model containing all prohibitions, which may include multiple prohibition rules with different predicate constraints
     * @return a list of models, each containing the statements relevant to a single prohibition rule, suitable for building separate NOT EXISTS branches in the query rewrite
     */
    private static List<Model> splitProhibitionsIntoModels(Model prohibitionsModel) {
        List<Model> out = new ArrayList<>();
        if (prohibitionsModel == null || prohibitionsModel.isEmpty()) {
            return out;
        }

        for (Statement st : prohibitionsModel.listStatements(null, ODRL.prohibition, (RDFNode) null).toList()) {
            RDFNode prohibNode = st.getObject();
            if (prohibNode == null || !prohibNode.isResource()) {
                continue;
            }

            Resource prohibRes = prohibNode.asResource();
            Model sub = ModelFactory.createDefaultModel();

            prohibitionsModel.listStatements(prohibRes, null, (RDFNode) null).forEachRemaining(sub::add);

            Statement targetSt = prohibRes.getProperty(ODRL.target);
            if (targetSt != null && targetSt.getObject() != null && targetSt.getObject().isResource()) {
                Resource targetRes = targetSt.getObject().asResource();
                prohibitionsModel.listStatements(targetRes, null, (RDFNode) null).forEachRemaining(sub::add);

                Property odrlRefinement = prohibitionsModel.createProperty(ODRL_NS + "refinement");
                for (Statement rSt : prohibitionsModel.listStatements(targetRes, odrlRefinement, (RDFNode) null).toList()) {
                    RDFNode refNode = rSt.getObject();
                    if (refNode != null && refNode.isAnon()) {
                        copyBlankNodeClosure(prohibitionsModel, sub, refNode.asResource());
                    }
                }
            }

            Statement assigneeSt = prohibRes.getProperty(ODRL.assignee);
            if (assigneeSt != null && assigneeSt.getObject() != null && assigneeSt.getObject().isResource()) {
                Resource assigneeRes = assigneeSt.getObject().asResource();
                prohibitionsModel.listStatements(assigneeRes, null, (RDFNode) null).forEachRemaining(sub::add);
            }

            out.add(sub);
        }

        return out;
    }

    /**
     * Builds a query that yields no results.
     *
     * Used as a "deny by default" outcome when no permission rules apply.
     * @return A SPARQL Query object that matches nothing.
     */
    private static Query emptyQuery() {
        String queryString = "SELECT * WHERE { }"; 
        return QueryFactory.create(queryString, Syntax.syntaxSPARQL_11);
    }

    /**
     * Collects graph IRIs referenced by the query.
     *
     * Sources:
     * - {@code GRAPH <iri> { ... }} clauses (named graph patterns)
     * - {@code FROM <iri>} dataset clauses
     *
     * This is used by {@link #normalizeQuery(Query)} to detect whether the query targets exactly one dataset/graph.
     * @param query The query from which to collect graph IRIs.
     * @return A set of graph IRIs used in the query.
     */
    public static Set<Node> collectGraphIris(Query query) {
        Set<Node> graphIris = new LinkedHashSet<>();
        Element originalPattern = query.getQueryPattern();
        
        // Collect from GRAPH <iri> clauses
        ElementWalker.walk(originalPattern, new ElementVisitorBase() {
            @Override
            public void visit(ElementNamedGraph el) {
                Node graphName = el.getGraphNameNode();
                if (graphName != null && graphName.isURI()) {
                    graphIris.add(graphName);
                }
            }
        });
        
        // Collect from FROM <...> clauses
        for (String graphUri : query.getGraphURIs()) {
            if (graphUri != null && !graphUri.isEmpty()) {
                graphIris.add(NodeFactory.createURI(graphUri));
            }
        }
        
        return graphIris;
    }

    /**
     * Rewrites {@code GRAPH <iri> { ... }} to {@code GRAPH ?g { ... }}.
     *
     * If the query has {@code FROM <...>} clauses, the entire pattern is additionally wrapped in {@code GRAPH ?g { ... }}
     * to make the graph explicit as a variable for downstream rewriting.
     */
    private static Element transformGraphClauses(Query query) {
        Element originalPattern = query.getQueryPattern();
        boolean hasFromClause = !query.getGraphURIs().isEmpty();
        
        // Rewrite GRAPH <...> {...} to GRAPH ?g {...}
        Element transformed = ElementTransformer.transform(originalPattern, new ElementTransformCopyBase() {
            @Override
            public Element transform(ElementNamedGraph el, Node graphName, Element subElt) {
                if (graphName != null && graphName.isURI()) {
                    return new ElementNamedGraph(varG, subElt);
                }
                return super.transform(el, graphName, subElt);
            }
        });
        
        // Wrap FROM queries in GRAPH ?g { ... }
        if (hasFromClause) {
            transformed = new ElementNamedGraph(varG, transformed);
        }
        
        return transformed;
    }

    /**
     * Adds (or reuses if already present) a {@code FILTER (?g = <graphIri>)}.
     */
    private static Element addGraphFilter(Element pattern, Node graphIri) {
        Expr filterExpr = new E_Equals(new ExprVar("g"), NodeValue.makeNode(graphIri));
        boolean hasGraphFilter = false;
        if (pattern instanceof ElementGroup group) {
            for (Element el : group.getElements()) {
                if (el instanceof ElementFilter ef && ef.getExpr().equals(filterExpr)) {
                    hasGraphFilter = true;
                    break;
                }
            }
            if (!hasGraphFilter) {
                group.addElement(new ElementFilter(filterExpr));
            }
            return group;
        } else {
            ElementGroup group = new ElementGroup();
            group.addElement(pattern);
            group.addElement(new ElementFilter(filterExpr));
            return group;
        }
    }

    /**
     * Adds (or reuses if already present) a {@code FILTER (?user = <userIRI>)}.
     */
    private static Element addUserFilter(Element pattern, User user) {
        Node userIri = NodeFactory.createURI(user.getUri());
        Expr filterExpr = new E_Equals(new ExprVar("user"), NodeValue.makeNode(userIri));
        boolean hasUserFilter = false;
        if (pattern instanceof ElementGroup group) {
            for (Element el : group.getElements()) {
                if (el instanceof ElementFilter ef && ef.getExpr().equals(filterExpr)) {
                    hasUserFilter = true;
                    break;
                }
            }
            if (!hasUserFilter) {
                group.addElement(new ElementFilter(filterExpr));
            }
            return group;
        } else {
            ElementGroup group = new ElementGroup();
            group.addElement(pattern);
            group.addElement(new ElementFilter(filterExpr));
            return group;
        }
    }

    // Constrains allowed predicates using VALUES over a dedicated variable (?p_acl),
    // joins it to the query predicate via FILTER(?p = ?p_acl), and applies any
    // refinement constraints as a conditional filter tied to ?p_acl.
    private static Element addPredicateValuesJoinAndRefinementFilters(Element pattern, PredicateConstraints constraints, String pAclVarName) {
        if (constraints == null || constraints.isEmpty()) {
            return pattern;
        }

        ElementGroup group;
        if (pattern instanceof ElementGroup g) {
            group = g;
        } else {
            group = new ElementGroup();
            group.addElement(pattern);
        }
		String varName = (pAclVarName == null || pAclVarName.isBlank()) ? "p_acl" : pAclVarName;

        Var pAcl = Var.alloc(varName);
        ElementData values = new ElementData();
        values.add(pAcl);

        boolean addedAny = false;
        for (Node iri : constraints.predicates) {
            if (iri == null || !iri.isURI()) {
                continue;
            }
            values.add(BindingFactory.binding(pAcl, iri));
            addedAny = true;
        }

        if (!addedAny) {
            return group;
        }

        // VALUES ?p_acl { ... }
        group.addElement(values);

        // FILTER (?p = ?p_acl)
        Expr join = new E_Equals(new ExprVar("p"), new ExprVar(varName));
        group.addElement(new ElementFilter(join));

        // Conditional refinement filter, e.g.:
        // FILTER( (?p_acl = <prop_1> && ?o <= 1200) || (?p_acl = <prop_2> && ?o >= 5) || (?p_acl = <prop_3>) )
        Expr disj = null;
        for (Node iri : constraints.predicates) {
            if (iri == null || !iri.isURI()) {
                continue;
            }
            Expr predEq = new E_Equals(new ExprVar(varName), NodeValue.makeNode(iri));
            Expr refinement = constraints.refinementByPredicate.get(iri);
            Expr branch = (refinement == null) ? predEq : new E_LogicalAnd(predEq, refinement);
            disj = (disj == null) ? branch : new E_LogicalOr(disj, branch);
        }
        if (disj != null) {
            group.addElement(new ElementFilter(disj));
        }

        return group;
    }

    /**
     * Builds a cloned query with a replaced query pattern and clears {@code FROM}/{@code FROM NAMED} clauses.
     *
     * Normalization makes the graph explicit in the pattern (via {@code GRAPH ?g { ... }}), so dataset clauses are
     * no longer needed.
     */
    private static Query buildNormalizedQuery(Query original, Element newPattern) {
        Query answer = original.cloneQuery();
        answer.getGraphURIs().clear();
        answer.getNamedGraphURIs().clear();
        answer.setQueryPattern(newPattern);
        return answer;
    }

    /**
     * TODO: This is not needed anymore!!
     * Rewrites blank nodes in the query pattern into a single fixed variable.
     * @param query the query to rewrite
     * @param variableName the variable name to use (without '?')
     * @return a cloned query with blank nodes replaced, or null if query/pattern is null
    */
    public static Query replaceBlankNodesWithVariable(Query query, String variableName) {
        if (query == null || query.getQueryPattern() == null) {
            return null;
        }
        if (variableName == null || variableName.trim().isEmpty()) {
            throw new IllegalArgumentException("variableName must not be null/blank");
        }

        String base = variableName.trim();
        // Use a stable variable name for the main query pattern.
        Node replacementVar = NodeFactory.createVariable(base);

        // Use a distinct variable name under NOT EXISTS to avoid accidental correlation
        // between the NOT EXISTS subpattern and the outer pattern.
        Node replacementVarNotExists = NodeFactory.createVariable(generateVariableName(null));
        Element originalPattern = query.getQueryPattern();
        Element transformed = replaceBlankNodesDeep(originalPattern, replacementVar, replacementVarNotExists);
        Query rewritten = query.cloneQuery();
        rewritten.setQueryPattern(transformed);
        return rewritten;
    }

    private static int counterVar = 0;

    /**
     * Generates a unique variable name.
     *
     * This avoids collisions when synthesizing fresh variables during transformations.
     */
    private static String generateVariableName(String base) {
        // NOTE: This is a simple counter-based approach.
        // A more robust implementation would inspect the query to avoid collisions with existing names.
        if (base == null) {
            base = "x";
        }
        counterVar++;
        return base + "_" + counterVar;
    }

    /**
     * Removes exact duplicate triple patterns from the query pattern. Two triple patterns are considered duplicates if they have the same subject, predicate, and object nodes (including variables).
     * @param query the query to check
     * @return a cloned query with duplicate triple patterns removed, or null if query/pattern is null
     */
    public static Query removeDuplicateTriplePatterns(Query query) {
        if (query == null || query.getQueryPattern() == null) {
            return null;
        }
        Element originalPattern = query.getQueryPattern();
        Element transformed = removeDuplicateTriplesDeep(originalPattern);
        Query rewritten = query.cloneQuery();
        rewritten.setQueryPattern(transformed);
        return rewritten;
    }

    /**
     * Recursively replaces blank nodes in the query pattern.
     *
     * This transformation preserves structure (GROUP/UNION/GRAPH) while rewriting only the RDF nodes in
     * triple/path blocks.
     */
    private static Element replaceBlankNodesDeep(Element element, Node replacementVar, Node replacementVarNotExists) {
        if (element instanceof ElementGroup group) {
            ElementGroup out = new ElementGroup();
            for (Element sub : group.getElements()) {
                out.addElement(replaceBlankNodesDeep(sub, replacementVar, replacementVarNotExists));
            }
            return out;
        }
        if (element instanceof ElementUnion union) {
            ElementUnion out = new ElementUnion();
            for (Element sub : union.getElements()) {
                out.addElement(replaceBlankNodesDeep(sub, replacementVar, replacementVarNotExists));
            }
            return out;
        }
        if (element instanceof ElementNamedGraph ng) {
            return new ElementNamedGraph(ng.getGraphNameNode(), replaceBlankNodesDeep(ng.getElement(), replacementVar, replacementVarNotExists));
        }
        if (element instanceof ElementFilter filter) {
            Expr expr = filter.getExpr();
            if (expr instanceof E_NotExists notExists) {
                // Use a different variable in NOT EXISTS to avoid correlating with outer patterns.
                Element inner2 = replaceBlankNodesDeep(notExists.getElement(), replacementVarNotExists, replacementVarNotExists);
                return new ElementFilter(new E_NotExists(inner2));
            }
            return filter;
        }
        if (element instanceof ElementTriplesBlock el) {
            ElementTriplesBlock out = new ElementTriplesBlock();
            el.patternElts().forEachRemaining(t -> {
                Node s = replaceIfBlank(t.getSubject(), replacementVar);
                Node o = replaceIfBlank(t.getObject(), replacementVar);
                out.addTriple(Triple.create(s, t.getPredicate(), o));
            });
            return out;
        }
        if (element instanceof ElementPathBlock el) {
            ElementPathBlock out = new ElementPathBlock();
            el.patternElts().forEachRemaining(tp -> {
                Node s = replaceIfBlank(tp.getSubject(), replacementVar);
                Node o = replaceIfBlank(tp.getObject(), replacementVar);
                if (tp.isTriple()) {
                    out.addTriple(Triple.create(s, tp.getPredicate(), o));
                } else {
                    out.addTriplePath(new TriplePath(s, tp.getPath(), o));
                }
            });
            return out;
        }
        return element;
    }

    /**
     * Recursively removes exact duplicate triple patterns.
     *
     * Duplicates are removed within each triple block/path block by tracking (s,p,o) keys.
     */
    private static Element removeDuplicateTriplesDeep(Element element) {
        if (element instanceof ElementGroup group) {
            ElementGroup out = new ElementGroup();
            for (Element sub : group.getElements()) {
                out.addElement(removeDuplicateTriplesDeep(sub));
            }
            return out;
        }
        if (element instanceof ElementUnion union) {
            ElementUnion out = new ElementUnion();
            for (Element sub : union.getElements()) {
                out.addElement(removeDuplicateTriplesDeep(sub));
            }
            return out;
        }
        if (element instanceof ElementNamedGraph ng) {
            return new ElementNamedGraph(ng.getGraphNameNode(), removeDuplicateTriplesDeep(ng.getElement()));
        }
        if (element instanceof ElementFilter filter) {
            Expr expr = filter.getExpr();
            if (expr instanceof E_NotExists notExists) {
                Element inner2 = removeDuplicateTriplesDeep(notExists.getElement());
                return new ElementFilter(new E_NotExists(inner2));
            }
            return filter;
        }
        if (element instanceof ElementTriplesBlock el) {
            ElementTriplesBlock out = new ElementTriplesBlock();
            Set<TripleKey> seen = new LinkedHashSet<>();
            el.patternElts().forEachRemaining(t -> {
                // Track exact (s,p,o) duplicates.
                if (seen.add(new TripleKey(t.getSubject(), t.getPredicate(), t.getObject()))) {
                    out.addTriple(t);
                }
            });
            return out;
        }
        if (element instanceof ElementPathBlock el) {
            ElementPathBlock out = new ElementPathBlock();
            Set<TripleKey> seen = new LinkedHashSet<>();
            el.patternElts().forEachRemaining(tp -> {
                if (tp.isTriple()) {
                    Node s = tp.getSubject();
                    Node p = tp.getPredicate();
                    Node o = tp.getObject();
                    if (seen.add(new TripleKey(s, p, o))) {
                        out.addTriple(Triple.create(s, p, o));
                    }
                } else {
                    out.addTriplePath(tp);
                }
            });
            return out;
        }
        return element;
    }

    /**
     * Helper to rewrite a blank node to a specific variable.
     */
    private static Node replaceIfBlank(Node n, Node replacementVar) {
        if (n != null && n.isBlank()) {
            return replacementVar;
        }
        return n;
    }

}