/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.traversal.algorithm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.core.MultivaluedMap;

import org.apache.tinkerpop.gremlin.structure.Edge;

import com.baidu.hugegraph.HugeGraph;
import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.structure.HugeEdge;
import com.baidu.hugegraph.type.define.Directions;
import com.baidu.hugegraph.util.E;
import com.baidu.hugegraph.util.OrderLimitMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

public class RankTraverser extends HugeTraverser {

    private final double alpha;

    public RankTraverser(HugeGraph graph, double alpha) {
        super(graph);
        this.alpha = alpha;
    }

    public List<Map<Id, Double>> neighborRank(Id source, List<Step> steps,
                                              long capacity, long limit) {
        E.checkArgument(!steps.isEmpty(), "The steps can't be empty");
        checkCapacity(capacity);
        checkLimit(limit);

        MultivaluedMap<Id, Node> sources = newMultivalueMap();
        sources.add(source, new Node(source, null));

        boolean sameLayerTransfer = true;
        int stepNum = steps.size();
        int pathCount = 0;
        long access = 0;
        // Result
        List<Map<Id, Double>> ranks = new ArrayList<>(stepNum + 1);
        ranks.add(ImmutableMap.of(source, 1.0));

        MultivaluedMap<Id, Node> newVertices;
        root : for (Step step : steps) {
            stepNum--;
            newVertices = newMultivalueMap();

            Map<Id, Double> lastLayerRanks = ranks.get(ranks.size() - 1);
            Map<Id, Double> sameLayerIncrRanks = new HashMap<>();
            MultivaluedMap<Id, Node> adjacencies = newMultivalueMap();
            // Traversal vertices of previous level
            for (Map.Entry<Id, List<Node>> entry : sources.entrySet()) {
                Id vertex = entry.getKey();
                Iterator<Edge> edges = edgesOfVertex(vertex, step.direction,
                                                     step.labels, null,
                                                     step.degree);

                long degree = 0L;
                Set<Id> sameLayerNodes = new HashSet<>();
                Map<Integer, Set<Id>> prevLayerNodes = new HashMap<>();
                while (edges.hasNext()) {
                    degree++;
                    HugeEdge edge = (HugeEdge) edges.next();
                    Id target = edge.id().otherVertexId();
                    // Determine whether it belongs to the same layer
                    if (belongToSameLayer(sources, target, sameLayerNodes)) {
                        continue;
                    }
                    /*
                     * Determine whether it belongs to the previous layers,
                     * if it belongs to, update the weight, but don't pass
                     * any more
                     */
                    if (belongToPrevLayers(ranks, target, prevLayerNodes)) {
                        continue;
                    }

                    for (Node n : entry.getValue()) {
                        // If have loop, skip target
                        if (n.contains(target)) {
                            continue;
                        }
                        Node newNode = new Node(target, n);
                        adjacencies.add(vertex, newNode);

                        checkCapacity(capacity, ++access, "neighbor ranks");
                    }
                }
                List<Node> adjacency = adjacencies.getOrDefault(vertex,
                                                   ImmutableList.of());
                assert degree == sameLayerNodes.size() + prevLayerNodes.size() +
                                 adjacency.size();

                // Add current node's adjacent nodes
                for (Node node : adjacency) {
                    newVertices.add(node.id(), node);
                    // Avoid exceeding limit
                    if (stepNum == 0) {
                        if (limit != NO_LIMIT && ++pathCount >= limit) {
                            break root;
                        }
                    }
                }
                double incr = lastLayerRanks.getOrDefault(vertex, 0.0) *
                              this.alpha / degree;
                // Save the contribution of the same layer node(increment)
                for (Id node : sameLayerNodes) {
                    sameLayerIncrRanks.put(node, incr);
                }
                // Adding contributions to the previous layers
                for (Map.Entry<Integer, Set<Id>> e : prevLayerNodes.entrySet()) {
                    Map<Id, Double> prevLayerRanks = ranks.get(e.getKey());
                    for (Id node : e.getValue()) {
                        double oldRank = prevLayerRanks.get(node);
                        prevLayerRanks.put(node, oldRank + incr);
                    }
                }
            }

            Map<Id, Double> newLayerRanks;
            if (sameLayerTransfer) {
                // First contribute to last layer, then pass to the new layer
                this.contributeLastLayer(sameLayerIncrRanks, lastLayerRanks);
                newLayerRanks = this.contributeNewLayer(adjacencies,
                                                        lastLayerRanks, step);
            } else {
                // First pass to the new layer, then contribute to last layer
                newLayerRanks = this.contributeNewLayer(adjacencies,
                                                        lastLayerRanks, step);
                this.contributeLastLayer(sameLayerIncrRanks, lastLayerRanks);
            }
            ranks.add(newLayerRanks);

            // Re-init sources
            sources = newVertices;
        }
        if (stepNum != 0) {
            return ImmutableList.of(ImmutableMap.of());
        }
        return this.topRanks(ranks, steps);
    }

    private boolean belongToSameLayer(MultivaluedMap<Id, Node> sources,
                                      Id target, Set<Id> sameLayerNodes) {
        if (sources.containsKey(target)) {
            sameLayerNodes.add(target);
            return true;
        } else {
            return false;
        }
    }

    private boolean belongToPrevLayers(List<Map<Id, Double>> ranks, Id target,
                                       Map<Integer, Set<Id>> prevLayerNodes) {
        for (int i = ranks.size() - 2; i > 0; i--) {
            Map<Id, Double> prevLayerRanks = ranks.get(i);
            if (prevLayerRanks.containsKey(target)) {
                Set<Id> nodes = prevLayerNodes.computeIfAbsent(i,
                                               k -> new HashSet<>());
                nodes.add(target);
                return true;
            }
        }
        return false;
    }

    private void contributeLastLayer(Map<Id, Double> rankIncrs,
                                     Map<Id, Double> lastLayerRanks) {
        for (Map.Entry<Id, Double> entry : rankIncrs.entrySet()) {
            double originRank = lastLayerRanks.get(entry.getKey());
            double incrRank = entry.getValue();
            lastLayerRanks.put(entry.getKey(), originRank + incrRank);
        }
    }

    private Map<Id, Double> contributeNewLayer(
                            MultivaluedMap<Id, Node> adjacencies,
                            Map<Id, Double> lastLayerRanks,
                            Step step) {
        Map<Id, Double> newLayerRanks = new OrderLimitMap<>(step.capacity);
        for (Map.Entry<Id, List<Node>> entry : adjacencies.entrySet()) {
            Id parent = entry.getKey();
            long size = entry.getValue().size();
            for (Node node : entry.getValue()) {
                double rank = newLayerRanks.getOrDefault(node.id(), 0.0);
                rank += (lastLayerRanks.get(parent) * this.alpha / size);
                newLayerRanks.put(node.id(), rank);
            }
        }
        return newLayerRanks;
    }

    private List<Map<Id, Double>> topRanks(List<Map<Id, Double>> ranks,
                                           List<Step> steps) {
        assert ranks.size() > 0;
        List<Map<Id, Double>> results = new ArrayList<>(ranks.size());
        // The first layer is root node so skip i=0
        results.add(ranks.get(0));
        for (int i = 1; i < ranks.size(); i++) {
            Step step = steps.get(i - 1);
            OrderLimitMap<Id, Double> origin = (OrderLimitMap<Id, Double>)
                                               ranks.get(i);
            if (origin.size() > step.top) {
                results.add(origin.topN(step.top));
            } else {
                results.add(origin);
            }
        }
        return results;
    }

    public static class Step {

        public static final int MAX_TOP = 1000;
        public static final int DEFAULT_CAPACITY_PER_LAYER = 100000;

        private final Directions direction;
        private final Map<Id, String> labels;
        private final long degree;
        private final int top;
        private final int capacity;

        public Step(Directions direction, Map<Id, String> labels,
                    long degree, int top) {
            this.direction = direction;
            this.labels = labels;
            this.degree = degree;
            this.top = top;
            this.capacity = DEFAULT_CAPACITY_PER_LAYER;
        }
    }
}