
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

## Query rewriting algorithm

The CLI always starts from a fixed graph query (the graph is provided as input):

```sparql
SELECT ?s ?p ?o WHERE { GRAPH <GRAPH_IRI> { ?s ?p ?o } }
```

Internally, the query is normalized to:

- `GRAPH ?g { ?s ?p ?o } FILTER (?g = <GRAPH_IRI>)`

…and then the rewrite injects policy enforcement.

## Policy folder used in the example

This repo contains a few examples of policy files:

- `src/main/resources/odrl_test/example_policies.ttl`
- `src/main/resources/odrl_test/example_readme.ttl`


### Examples

```
prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
prefix odrl: <http://www.w3.org/ns/odrl/2/> 
prefix champ-inst: <https://resource.champi40ns.eu/>
prefix champ-onto: <https://schema.champi40ns.eu#>
prefix vcard: <http://www.w3.org/2006/vcard/ns#>


champ-inst:Product_props1_Asset a odrl:Asset ;
    odrl:partOf <https://data.champi40ns.eu/joinery-product> ; 
    rdf:predicate champ-onto:wasteInPercentage ;
    rdf:predicate champ-onto:wasteFromTrunkInPercentage .


champ-inst:Product_props2_Asset a odrl:Asset ;
    odrl:partOf <https://data.champi40ns.eu/joinery-product> ; 
    rdf:predicate champ-onto:moistureContentInPercentage .


champ-inst:policyAllowDatasetProperties a odrl:Set ;

    odrl:permission [
		odrl:target champ-inst:Forest_props1_Asset ;
		odrl:action odrl:read ; 
		odrl:assignee champ-inst:user_alice
    ] .

champ-inst:policyProhibitDatasetProperties a odrl:Set ;

    odrl:prohibition [
		odrl:target champ-inst:Forest_props2_Asset ;
		odrl:action odrl:read ; 
		odrl:assignee champ-inst:user_alice
    ] .
```

For the graph `<https://data.champi40ns.eu/joinery-product>` and user `<https://resource.champi40ns.eu/user_alice>`:

- **Permission**: allow reading the predicates
  - `<https://schema.champi40ns.eu#wasteInPercentage>`
  - `<https://schema.champi40ns.eu#wasteFromTrunkInPercentage>`
- **Prohibition**: deny reading the predicate
  - `<https://schema.champi40ns.eu#moistureContentInPercentage>`

Those predicates come from `rdf:predicate` statements attached to the policy’s `odrl:target` assets.

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

## Example output (rewritten query)

Running the command above prints a rewritten query similar to the following:

```sparql
SELECT  ?s ?p ?o
WHERE
  { GRAPH ?g
      { ?s  ?p  ?o }
    FILTER ( ?g = <https://data.champi40ns.eu/joinery-product> )
    { GRAPH <urn:acl>
        { _:b0      <http://www.w3.org/ns/odrl/2/target>  <https://resource.champi40ns.eu/Forest_prop1_Asset> ;
                    <http://www.w3.org/ns/odrl/2/action>  <http://www.w3.org/ns/odrl/2/read> ;
                    <http://www.w3.org/ns/odrl/2/assignee>  ?user .
          <https://resource.champi40ns.eu/Product_prop1_Asset>
                    a                     <http://www.w3.org/ns/odrl/2/Asset> ;
                    <http://www.w3.org/ns/odrl/2/partOf>  ?g
        }
    }
    FILTER ( ?p IN (<https://schema.champi40ns.eu#wasteInPercentage>, <https://schema.champi40ns.eu#wasteFromTrunkInPercentage>) )
    FILTER ( ?p NOT IN (<https://schema.champi40ns.eu#moistureContentInPercentage>) )
    FILTER ( ?user = <https://resource.champi40ns.eu/user_alice> )
  }
```


