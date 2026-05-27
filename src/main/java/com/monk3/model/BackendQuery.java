package com.monk3.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monk3.search.SearchEngine;

import java.util.List;

public record BackendQuery(String backend, SearchEngine engine, List<String> materialTypes, ObjectNode query) {}
