
# champI4.0ns DPP Knowledge Graph

This repository contains champI4.0ns Digital Product Passport (DPP) Knowledge Graph and the prototypical implementation for access controls in Java.

## Repository layout

- `input/` — source CSV files used as logical sources for the mappings (forest → transport → sawmill → joinery)
- `mappings/` — RML mappings (CSV → RDF)
- `ontology/` — ontology (TBox)
- `shapes/` — SHACL shapes for validating generated RDF
- `etl/` — ETL SPARQL update queries to evolve the Knowledge Graph
- `acl/` — Implementation in Java (Jena API) for access-control / policies

## Prefixes

- Instance namespace: `https://resource.champi40ns.eu/`
- Ontology namespace: `https://schema.champi40ns.eu#`
- Named graphs used by the mappings:
	- `https://data.champi40ns.eu/forest`
	- `https://data.champi40ns.eu/sawmill-arrival`
	- `https://data.champi40ns.eu/sawmill-output`
	- `https://data.champi40ns.eu/joinery-arrival`
	- `https://data.champi40ns.eu/joinery-product`
	- `https://data.champi40ns.eu/transport`


# SPARQL Query Rewriting Based on Access Control Rules

![Workflow](images/Access%20controls%20workflow.jpg)

A **prototypical** Java (Apache Jena / ARQ) implementation that rewrites a SPARQL query to enforce **ODRL** read permissions/prohibitions.

The CLI takes as input the following:
- a **user** IRI
- a **named graph** IRI
- a folder containing one or more **ODRL Turtle policy files (`.ttl`)**

…and returns/prints the **rewritten SPARQL query**.

## Policy folder used in the example

This repo contains a few examples of policy files:

- `src/main/resources/odrl_test/example_policies.ttl`
- `src/main/resources/odrl_test/example_readme.ttl`

## Run the CLI (Terminal)

From the repo root:

```bash
mvn -q -DskipTests package
mvn -q -DincludeScope=runtime -Dmdep.outputFile=target/classpath.txt dependency:build-classpath
CP="$(cat target/classpath.txt):target/classes"

java -cp "$CP" aclshacl.Main_CLI \
  --user-uri https://resource.champi40ns.eu/user_alice \
  --graph https://data.champi40ns.eu/joinery-product \
  --odrl-folder src/main/resources/odrl_test/
```


## Examples

### Example 1

```
prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
prefix odrl: <http://www.w3.org/ns/odrl/2/> 
prefix champ-inst: <https://resource.champi40ns.eu/>
prefix champ-onto: <https://schema.champi40ns.eu#>
prefix vcard: <http://www.w3.org/2006/vcard/ns#>


champ-inst:policyAllowDataset a odrl:Set ;
    odrl:permission [ 
		odrl:target <https://data.champi40ns.eu/forest> ;
		odrl:action odrl:read ;
		odrl:assignee champ-inst:user_alice ] .
```

Rewriting:


```sparql
SELECT  ?s ?p ?o
WHERE
  { GRAPH ?g
      { ?s  ?p  ?o }
    FILTER ( ?g = <https://data.champi40ns.eu/forest> )
    { GRAPH <urn:acl>
        { _:b0  <http://www.w3.org/ns/odrl/2/target>  ?g ;
                <http://www.w3.org/ns/odrl/2/action>  <http://www.w3.org/ns/odrl/2/read> ;
                <http://www.w3.org/ns/odrl/2/assignee>  ?user}
    }
    FILTER ( ?user = <https://resource.champi40ns.eu/user_alice> )
  }
```


### Example 2

```
prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
prefix odrl: <http://www.w3.org/ns/odrl/2/> 
prefix champ-inst: <https://resource.champi40ns.eu/>
prefix champ-onto: <https://schema.champi40ns.eu#>
prefix vcard: <http://www.w3.org/2006/vcard/ns#>


champ-inst:HarvesterRole a odrl:PartyCollection ;
    vcard:hasMember champ-inst:user_alice ,
                champ-inst:user_bob .
                
champ-inst:Forest_prop1_Asset a odrl:Asset ;
    odrl:partOf <https://data.champi40ns.eu/forest> ; 
    rdf:predicate champ-onto:moistureContentInPercentage .

champ-inst:policyAllowDatasetProperty a odrl:Set ;
    odrl:permission [
        odrl:target champ-inst:Forest_prop1_Asset ;
        odrl:action odrl:read ;
        odrl:assignee champ-inst:HarvesterRole ] .
```

Rewriting:


```sparql
SELECT  ?s ?p ?o
WHERE
  { GRAPH ?g
      { ?s  ?p  ?o }
    FILTER ( ?g = <https://data.champi40ns.eu/forest> )
    { GRAPH <urn:acl>
        { _:b0      <http://www.w3.org/ns/odrl/2/target>  <https://resource.champi40ns.eu/Forest_prop1_Asset> ;
                    <http://www.w3.org/ns/odrl/2/action>  <http://www.w3.org/ns/odrl/2/read> ;
                    <http://www.w3.org/ns/odrl/2/assignee>  <https://resource.champi40ns.eu/HarvesterRole> .
          <https://resource.champi40ns.eu/HarvesterRole>
                    a                     <http://www.w3.org/ns/odrl/2/PartyCollection> ;
                    <http://www.w3.org/2006/vcard/ns#hasMember>  ?user .
          <https://resource.champi40ns.eu/Forest_prop1_Asset>
                    a                     <http://www.w3.org/ns/odrl/2/Asset> ;
                    <http://www.w3.org/ns/odrl/2/partOf>  ?g}
    }
    FILTER ( ?p IN (<https://schema.champi40ns.eu#moistureContentInPercentage>) )
    FILTER ( ?user = <https://resource.champi40ns.eu/user_alice> )
  }
```


### Example 3

```
prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
prefix odrl: <http://www.w3.org/ns/odrl/2/> 
prefix champ-inst: <https://resource.champi40ns.eu/>
prefix champ-onto: <https://schema.champi40ns.eu#>
prefix vcard: <http://www.w3.org/2006/vcard/ns#>


champ-inst:ProductView_1 a odrl:Asset ;
    odrl:partOf <https://data.champi40ns.eu/joinery-product> ;
    odrl:refinement [
        odrl:leftOperand champ-onto:wasteInPercentage ;
        odrl:operator odrl:lteq ;
        odrl:rightOperand 50 ] .

champ-inst:policyAllowDatasetProperty a odrl:Set ;
	odrl:permission [
        odrl:target champ-inst:ProductView_1 ;
        odrl:assignee champ-inst:user_alice ;
        odrl:action odrl:read ] .
```

Rewriting:


```sparql
SELECT  ?s ?p ?o
WHERE
  { GRAPH ?g
      { ?s  ?p  ?o }
    FILTER ( ?g = <https://data.champi40ns.eu/joinery-product> )
    { GRAPH <urn:acl>
        { <https://resource.champi40ns.eu/ProductView_1>
                    a                     <http://www.w3.org/ns/odrl/2/Asset> ;
                    <http://www.w3.org/ns/odrl/2/partOf>  ?g .
          _:b0      <http://www.w3.org/ns/odrl/2/target>  <https://resource.champi40ns.eu/ProductView_1> ;
                    <http://www.w3.org/ns/odrl/2/assignee>  ?user ;
                    <http://www.w3.org/ns/odrl/2/action>  <http://www.w3.org/ns/odrl/2/read>}
      VALUES ?p_acl { <https://schema.champi40ns.eu#wasteInPercentage> }
      FILTER ( ?p = ?p_acl )
      FILTER ( ( ?p_acl = <https://schema.champi40ns.eu#wasteInPercentage> ) && ( ?o <= 50 ) )
    }
    FILTER ( ?p IN (<https://schema.champi40ns.eu#wasteInPercentage>) )
    FILTER ( ?user = <https://resource.champi40ns.eu/user_alice> )
  }
```



### Example 4

```
prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
prefix odrl: <http://www.w3.org/ns/odrl/2/> 
prefix champ-inst: <https://resource.champi40ns.eu/>
prefix champ-onto: <https://schema.champi40ns.eu#>
prefix vcard: <http://www.w3.org/2006/vcard/ns#>


champ-inst:ProductView_2 a odrl:Asset ;
    odrl:partOf <https://data.champi40ns.eu/joinery-product> ;
    odrl:refinement [
        odrl:and (
            [
              odrl:leftOperand champ-onto:wasteInPercentage ;
              odrl:operator odrl:lteq ;
              odrl:rightOperand 40
            ]
            [
              odrl:leftOperand champ-onto:wasteInPercentage ;
              odrl:operator odrl:gteq ;
              odrl:rightOperand 30
            ]) ] .

champ-inst:policyDisallowDatasetProperty a odrl:Set ;
    odrl:prohibition [
        odrl:target champ-inst:ProductView_2 ;
        odrl:assignee champ-inst:user_alice ;
        odrl:action odrl:read ] .
```

Rewriting:


```sparql
SELECT  ?s ?p ?o
WHERE
  { GRAPH ?g
      { ?s  ?p  ?o }
    FILTER ( ?g = <https://data.champi40ns.eu/joinery-product> )
    FILTER NOT EXISTS { { GRAPH <urn:acl>
                            { _:b0      <http://www.w3.org/ns/odrl/2/target>  <https://resource.champi40ns.eu/ProductView_2> ;
                                        <http://www.w3.org/ns/odrl/2/assignee>  ?user ;
                                        <http://www.w3.org/ns/odrl/2/action>  <http://www.w3.org/ns/odrl/2/read> .
                              <https://resource.champi40ns.eu/ProductView_2>
                                        a                     <http://www.w3.org/ns/odrl/2/Asset> ;
                                        <http://www.w3.org/ns/odrl/2/partOf>  ?g}
                          FILTER ( ( ?p = <https://schema.champi40ns.eu#wasteInPercentage> ) && ( ( ?o <= 40 ) && ( ?o >= 30 ) ) )
                        }
                      }
    FILTER ( ?user = <https://resource.champi40ns.eu/user_alice> )
  }
```