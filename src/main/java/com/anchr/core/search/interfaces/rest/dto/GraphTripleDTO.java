package com.anchr.core.search.interfaces.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GraphTripleDTO class: represents a triple in a graph.
 * s (subject) represents the subject.
 * p (predicate) represents the predicate.
 * o (object) represents the object.
 */

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GraphTripleDTO {
    // Subject
    private String s;
    // Predicate
    private String p;
    // Object
    private String o;
}
