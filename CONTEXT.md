# Engineering Knowledge

This context defines the stable language for engineering knowledge before it is adapted, persisted, or projected for search.

## Language

**Knowledge Object**:
A stable, traceable unit of engineering knowledge with a source, content hash, and explicit lifecycle classification.
_Avoid_: Article, chunk, row

**Canonical Knowledge Object**:
A knowledge object that represents an authoritative source fact and has no derived-generation provenance.
_Avoid_: Primary copy, base record

**Derived Knowledge Object**:
A knowledge object produced from a canonical input by a named generator and version; it retains the consumed input identity and hash.
_Avoid_: Cached object, secondary record

**Relation**:
A directed, typed assertion between two stable knowledge-object identities. Its identity is independent of the evidence that supports it.
_Avoid_: Link, association

**Evidence**:
An auditable source-based observation that supports a relation assertion. A persisted relation has one or more evidence records.
_Avoid_: Proof row, annotation

**Knowledge Projection**:
An atomic candidate set of knowledge-object writes, relation assertions with evidence, and rebuildable index-state writes.
_Avoid_: Legacy document view, search index

**Index State**:
The recoverable status of a rebuildable projection for a specific object and canonical input identity.
_Avoid_: Search result, authoritative content
