package io.fpmlcdm.core.party;

import java.util.Objects;

public final class PartyInfo {
    
    private final String id;
    private final String name;
    private final String legalEntityId;
    private final String country;
    private final String role;
    
    private PartyInfo(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.legalEntityId = builder.legalEntityId;
        this.country = builder.country;
        this.role = builder.role;
    }
    
    public String getId() { return id; }
    public String getName() { return name; }
    public String getLegalEntityId() { return legalEntityId; }
    public String getCountry() { return country; }
    public String getRole() { return role; }
    
    public boolean hasLegalEntityId() { return legalEntityId != null && !legalEntityId.isEmpty(); }
    
    public boolean hasCountry() { return country != null && !country.isEmpty(); }
    
    public static Builder builder() { return new Builder(); }
    
    public Builder toBuilder() {
        return new Builder()
            .id(id)
            .name(name)
            .legalEntityId(legalEntityId)
            .country(country)
            .role(role);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PartyInfo partyInfo = (PartyInfo) o;
        return Objects.equals(id, partyInfo.id) &&
               Objects.equals(name, partyInfo.name) &&
               Objects.equals(legalEntityId, partyInfo.legalEntityId) &&
               Objects.equals(country, partyInfo.country) &&
               Objects.equals(role, partyInfo.role);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id, name, legalEntityId, country, role);
    }
    
    @Override
    public String toString() {
        return String.format("PartyInfo{id='%s', name='%s', role='%s'}", id, name, role);
    }
    
    public static final class Builder {
        private String id;
        private String name;
        private String legalEntityId;
        private String country;
        private String role;
        
        public Builder id(String id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder legalEntityId(String legalEntityId) { this.legalEntityId = legalEntityId; return this; }
        public Builder country(String country) { this.country = country; return this; }
        public Builder role(String role) { this.role = role; return this; }
        
        public PartyInfo build() { return new PartyInfo(this); }
    }
}
