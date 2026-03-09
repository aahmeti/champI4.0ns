package aclshacl;

import java.util.Objects;

/**
 * Represents a user in the ODRL access control system.
 */
public class User {
    
    private final String uri;
    private final String name;

    public User(String uri) {
        this(uri, null);
    }

    public User(String uri, String name) {
        if (uri == null || uri.trim().isEmpty()) {
            throw new IllegalArgumentException("User URI must not be null or blank");
        }
        this.uri = uri.trim();
        this.name = name;
    }

    public String getUri() {
        return uri;
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return uri.equals(user.uri);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri);
    }

    @Override
    public String toString() {
        return name != null ? name + " (" + uri + ")" : uri;
    }
}
