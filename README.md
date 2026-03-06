
# champI4.0ns DPP Knowledge Graph

This repository contains champI4.0ns Digital Product Passport (DPP) Knowledge Graph and the implementation for access controls in Java.

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
	- `https://data.champi40ns.eu/forest/`
	- `https://data.champi40ns.eu/sawmill-arrival/`
	- `https://data.champi40ns.eu/sawmill-output/`
	- `https://data.champi40ns.eu/joinery-arrival/`
	- `https://data.champi40ns.eu/joinery-product/`
	- `https://data.champi40ns.eu/transport/`