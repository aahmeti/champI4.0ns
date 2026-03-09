package aclshacl;

import org.apache.jena.graph.Node;

/**
 * Value object used for de-duplicating exact triple patterns by (s, p, o).
 */
final class TripleKey {
    private final Node s;
    private final Node p;
    private final Node o;

    TripleKey(Node s, Node p, Node o) {
        this.s = s;
        this.p = p;
        this.o = o;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TripleKey k)) {
            return false;
        }
        return s.equals(k.s) && p.equals(k.p) && o.equals(k.o);
    }

    @Override
    public int hashCode() {
        int result = s.hashCode();
        result = 31 * result + p.hashCode();
        result = 31 * result + o.hashCode();
        return result;
    }
}