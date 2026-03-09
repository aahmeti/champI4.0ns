package aclshacl;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

public record ODRLPolicyModels(Model permissions, Model prohibitions) {
    public ODRLPolicyModels {
        permissions = (permissions != null) ? permissions : ModelFactory.createDefaultModel();
        prohibitions = (prohibitions != null) ? prohibitions : ModelFactory.createDefaultModel();
    }

    public boolean hasPermissions() { return !permissions.isEmpty(); }
    public boolean hasProhibitions() { return !prohibitions.isEmpty(); }
    
    public Model getPermissions() { return permissions(); }
    public Model getProhibitions() { return prohibitions(); }
}