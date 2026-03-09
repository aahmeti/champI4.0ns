package aclshacl;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.ResourceFactory;

/**
 * ODRL 2.0 vocabulary terms.
 */
public class ODRL {
    
    public static final String NS = "http://www.w3.org/ns/odrl/2/";
    
    public static final Property permission = property("permission");
    public static final Property prohibition = property("prohibition");
    public static final Property target = property("target");
    public static final Property assignee = property("assignee");
    public static final Property partOf = property("partOf");
    public static final Property action = property("action");
    
    private static Property property(String local) {
        return ResourceFactory.createProperty(NS + local);
    }
    
    private ODRL() {
        throw new UnsupportedOperationException("Vocabulary class");
    }
}