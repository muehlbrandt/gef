/*******************************************************************************
 * Copyright (c) 2012, 2014 itemis AG and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Rene Kuhlemann - provided first version of code based on the initial paper
 *    		of Sugiyama et al. (http://dx.doi.org/10.1109/TSMC.1981.4308636),
 *          associated to bugzilla entry #384730  
 *    Adam Kovacs - implements the new LayerProvider and 
 *    		CrossingReducer interfaces
 *    
 *******************************************************************************/
package org.eclipse.gef4.layout.algorithms;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.eclipse.gef4.geometry.planar.Dimension;
import org.eclipse.gef4.geometry.planar.Rectangle;
import org.eclipse.gef4.layout.LayoutAlgorithm;
import org.eclipse.gef4.layout.LayoutPropertiesHelper;
import org.eclipse.gef4.layout.interfaces.ConnectionLayout;
import org.eclipse.gef4.layout.interfaces.LayoutContext;
import org.eclipse.gef4.layout.interfaces.NodeLayout;

/**
 * The SugiyamaLayoutAlgorithm class implements an algorithm to arrange a
 * directed graph in a layered tree-like layout. The final presentation follows
 * five design principles for enhanced readability:
 * 
 * - Hierarchical layout of vertices - Least crossings of lines (edges) -
 * Straightness of lines when ever possible - Close layout of vertices connected
 * to each other, i.e. short paths - Balanced layout of lines coming into or
 * going from a vertex
 * 
 * For further information see http://dx.doi.org/10.1109/TSMC.1981.4308636
 * 
 * This layout algorithm works only with - directed graphs (otherwise an
 * appropriate RuntimeException is thrown)
 * 
 * @author Rene Kuhlemann
 * @author Adam Kovacs
 */
public class SugiyamaLayoutAlgorithm implements LayoutAlgorithm {

	public enum Direction {
		HORIZONTAL, VERTICAL
	};

	/**
	 * 
	 * An interface for heuristics that reduces edge crossings.
	 * 
	 * @author Adam Kovacs
	 */
	public static interface CrossingReducer {
		/**
		 * From the given nodes it creates a map of NodeLayouts and NodeWrappers
		 * which contains the layers and indexes of the nodes
		 * 
		 * @param nodes
		 *            List of nodes needed to be organized
		 */
		void crossReduction(List<List<NodeWrapper>> nodes);
	}

	/**
	 * @author Rene Kuhlemann
	 */
	public static class BarycentricCrossingReducer implements CrossingReducer {

		private List<List<NodeWrapper>> layers = new ArrayList<List<NodeWrapper>>();
		private Map<NodeLayout, NodeWrapper> map = new IdentityHashMap<NodeLayout, NodeWrapper>();
		private static final int MAX_SWEEPS = 35;
		private int last; // index of the last element in a layer after padding
							// process

		/**
		 * Fills in virtual nodes, so the layer system finally becomes an
		 * equidistant grid
		 */
		private void padLayers() {
			last = 0;
			for (List<NodeWrapper> iter : layers)
				if (iter.size() > last)
					last = iter.size();
			last--; // index of the last element of any layer
			for (List<NodeWrapper> iter : layers) { // padding is always
													// added at
				// the END of each layer!
				for (int i = iter.size(); i <= last; i++)
					iter.add(new NodeWrapper());
				updateIndex(iter);
			}
		}

		/**
		 * Reduces connection crossings between two adjacent layers by a
		 * combined top-down and bottom-up approach. It uses a heuristic
		 * approach based on the predecessor's barycenter.
		 */
		private void reduceCrossings() {
			for (int round = 0; round < MAX_SWEEPS; round++) {
				if ((round & 1) == 0) { // if round is even then do a bottom-up
										// scan
					for (int index = 1; index < layers.size(); index++)
						reduceCrossingsDown(layers.get(index));
				} else { // else top-down
					for (int index = layers.size() - 2; index >= 0; index--)
						reduceCrossingsUp(layers.get(index));
				}
			}
		}

		private void reduceCrossingsDown(List<NodeWrapper> layer) {
			// DOWN: scan PREDECESSORS
			for (NodeWrapper node : layer)
				node.index = node.getBaryCenter(node.pred);
			Collections.sort(layer, new Comparator<NodeWrapper>() {
				public int compare(NodeWrapper node1, NodeWrapper node2) {
					return (node1.index - node2.index);
				}
			});
			updateIndex(layer);
		}

		private void reduceCrossingsUp(List<NodeWrapper> layer) {
			// UP: scan SUCCESSORS
			for (NodeWrapper node : layer)
				node.index = node.getBaryCenter(node.succ);
			Collections.sort(layer, new Comparator<NodeWrapper>() {
				public int compare(NodeWrapper node1, NodeWrapper node2) {
					return (node1.index - node2.index);
				}
			});
			updateIndex(layer);
		}

		private void refineLayers() { // from
										// Sugiyama
			// paper: down,
			// up and down
			// again yields best results, wonder why...
			for (int index = 1; index < layers.size(); index++)
				refineLayersDown(layers.get(index));
			for (int index = layers.size() - 2; index >= 0; index--)
				refineLayersUp(layers.get(index));
			for (int index = 1; index < layers.size(); index++)
				refineLayersDown(layers.get(index));
		}

		private void refineLayersDown(List<NodeWrapper> layer) {
			// first, get a priority list
			List<NodeWrapper> list = new ArrayList<NodeWrapper>(layer);
			Collections.sort(list, new Comparator<NodeWrapper>() {
				public int compare(NodeWrapper node1, NodeWrapper node2) {
					return (node2.getPriorityDown() - node1.getPriorityDown()); // descending
					// ordering!!!
				}
			});
			// second, remove padding from the layer's end and place them in
			// front
			// of the current node to improve its position
			for (NodeWrapper iter : list) {
				if (iter.isPadding())
					break; // break, if there are no more "real" nodes
				int delta = iter.getBaryCenter(iter.pred) - iter.index; // distance
				// to new
				// position
				for (int i = 0; i < delta; i++)
					layer.add(iter.index, layer.remove(last));
			}
			updateIndex(layer);
		}

		private void refineLayersUp(List<NodeWrapper> layer) {
			// first, get a priority list
			List<NodeWrapper> list = new ArrayList<NodeWrapper>(layer);
			Collections.sort(list, new Comparator<NodeWrapper>() {
				public int compare(NodeWrapper node1, NodeWrapper node2) {
					return (node2.getPriorityUp() - node1.getPriorityUp()); // descending
					// ordering!!!
				}
			});
			// second, remove padding from the layer's end and place them in
			// front
			// of the current node to improve its position
			for (NodeWrapper iter : list) {
				if (iter.isPadding())
					break; // break, if there are no more "real" nodes
				int delta = iter.getBaryCenter(iter.succ) - iter.index; // distance
				// to new
				// position
				for (int i = 0; i < delta; i++)
					layer.add(iter.index, layer.remove(last));
			}
			updateIndex(layer);
		}

		private void updateIndex(List<NodeWrapper> list) {
			for (int index = 0; index < list.size(); index++) {
				list.get(index).index = index;
				map.put(list.get(index).node, list.get(index));
			}
		}

		public void crossReduction(List<List<NodeWrapper>> nodes) {
			this.layers = nodes;
			padLayers();
			for (int i = 0; i < layers.size(); i++) { // reduce and
				// refine
				// iteratively, depending on
				// the depth of the graph
				reduceCrossings();
				refineLayers();
			}
			reduceCrossings();
		}
	}

	/**
	 * Implements the CrossingReducer interface. This algorithm divides each
	 * layer by a pivot node based on the relative position of connected nodes
	 * and decides which side of the pivot point it should be for the fewer edge
	 * crossing.
	 * 
	 * @author Adam Kovacs
	 * 
	 */
	public static class SplitCrossingReducer implements CrossingReducer {
		private final Map<NodeLayout, NodeWrapper> map = new IdentityHashMap<NodeLayout, NodeWrapper>();

		/**
		 * Filters the multiple connections from the two arrays
		 * 
		 * @param a
		 * @param b
		 * @return
		 */
		private ArrayList<NodeLayout> unionOfNodes(NodeLayout[] a,
				NodeLayout[] b) {
			ArrayList<NodeLayout> res = new ArrayList<NodeLayout>();

			for (int i = 0; i < a.length; i++)
				res.add(a[i]);
			for (int i = 0; i < b.length; i++)
				if (!res.contains(b[i]))
					res.add(b[i]);

			return res;
		}

		/**
		 * Returns the number of crosses between the two nodes and those
		 * connected to them.
		 * 
		 * @param nodeA
		 * @param nodeB
		 * @return
		 */
		private int numberOfCrosses(NodeWrapper nodeA, NodeWrapper nodeB) {
			int numOfCrosses = 0;
			if (nodeA.equals(nodeB))
				return 0;

			// Filter nodes connected with bidirectional edges
			ArrayList<NodeLayout> adjacentNodesOfA = unionOfNodes(
					nodeA.node.getPredecessingNodes(),
					nodeA.node.getSuccessingNodes());
			ArrayList<NodeLayout> adjacentNodesOfB = unionOfNodes(
					nodeB.node.getPredecessingNodes(),
					nodeB.node.getSuccessingNodes());

			for (NodeLayout aNode : adjacentNodesOfA) {
				ArrayList<Integer> alreadyCrossed = new ArrayList<Integer>();
				NodeWrapper aNodeWrapper = map.get(aNode);
				for (int i = 0; i < adjacentNodesOfB.size(); i++) {
					NodeWrapper nw = map.get(adjacentNodesOfB.get(i));
					if (!alreadyCrossed.contains(i) && nw != null) {
						// only if on the same side
						if ((nw.layer > nodeA.layer && aNodeWrapper.layer > nodeA.layer)
								|| (nw.layer < nodeA.layer && aNodeWrapper.layer < nodeA.layer)) {
							if (nodeA.index < nodeB.index) {
								if (aNodeWrapper.index > nw.index) {
									numOfCrosses++;
									alreadyCrossed.add(i);
								} else if (nw.index == aNodeWrapper.index) {
									if (nodeA.index >= nw.index) {
										// implies nodeB.index > nw.index
										if ((aNodeWrapper.layer > nw.layer && nodeA.layer < nw.layer)
												|| (aNodeWrapper.layer < nw.layer && nw.layer < nodeA.layer)) {
											// top-left or bottom-left quarter
											numOfCrosses++;
											alreadyCrossed.add(i);
										}
									} else if (nodeB.index <= nw.index) {
										// implies nodeA.index < nw.index
										if ((aNodeWrapper.layer > nw.layer && aNodeWrapper.layer < nodeB.layer)
												|| (aNodeWrapper.layer < nw.layer && aNodeWrapper.layer > nodeB.layer)) {
											// top-right or bottom-right quarter
											numOfCrosses++;
											alreadyCrossed.add(i);
										}
									}
								}
							} else if (nodeA.index > nodeB.index) {
								if (aNodeWrapper.index < nw.index) {
									numOfCrosses++;
									alreadyCrossed.add(i);
								} else if (nw.index == aNodeWrapper.index) {
									if (nodeB.index >= nw.index) {
										// implies nodeB.index > nw.index
										if ((aNodeWrapper.layer > nw.layer && nodeB.layer > aNodeWrapper.layer)
												|| (aNodeWrapper.layer < nw.layer && aNodeWrapper.layer > nodeB.layer)) {
											// top-left or bottom-left quarter
											numOfCrosses++;
											alreadyCrossed.add(i);
										}
									} else if (nodeA.index <= nw.index) {
										// implies nodeA.index < nw.index
										if ((aNodeWrapper.layer > nw.layer && nw.layer > nodeA.layer)
												|| (aNodeWrapper.layer < nw.layer && nw.layer < nodeA.layer)) {
											// top-right or bottom-right quarter
											numOfCrosses++;
											alreadyCrossed.add(i);
										}
									}
								}
							}
						}
					}
				}
			}

			return numOfCrosses;
		}

		/**
		 * Selects the pivot node by random and decides the order.
		 * 
		 * @param layer
		 * @return
		 */
		private List<NodeWrapper> splitHeuristic(List<NodeWrapper> layer) {
			ArrayList<NodeWrapper> left = new ArrayList<NodeWrapper>();
			ArrayList<NodeWrapper> right = new ArrayList<NodeWrapper>();

			if (layer.size() < 1)
				return layer;
			Random random = new Random();
			NodeWrapper pivot = layer.get(random.nextInt(layer.size()));
			// NodeWrapper pivot = layer.get(0);
			// NodeWrapper pivot = layer.get((int)(layer.size() / 2));
			for (NodeWrapper node : layer) {
				if (!node.equals(pivot) && node.node != null
						&& pivot.node != null) {
					int num1 = numberOfCrosses(node, pivot);
					int num2 = numberOfCrosses(pivot, node);
					if (num1 < num2)
						left.add(node);
					else if (num1 > num2)
						right.add(node);
					else {
						if (num1 == num2 && num1 > 0) {
							int tmpindex = map.get(pivot.node).index;
							map.get(pivot.node).index = map.get(node.node).index;
							map.get(node.node).index = tmpindex;
						}
						if (node.index < pivot.index)
							left.add(node);
						else
							right.add(node);
					}
				}
			}

			ArrayList<NodeWrapper> res = new ArrayList<NodeWrapper>();
			res.addAll(splitHeuristic(left));
			res.add(pivot);
			res.addAll(splitHeuristic(right));
			return res;
		}

		public void crossReduction(List<List<NodeWrapper>> nodes) {
			// Building the map
			for (List<NodeWrapper> layer : nodes)
				for (NodeWrapper nw : layer)
					map.put(nw.node, nw);
			for (int i = 0; i < nodes.size(); i++) {
				if (!nodes.get(i).isEmpty()) {
					splitHeuristic(nodes.get(i));
				}
			}
		}
	}

	/**
	 * Implemented the CrossingReducer interface. This algorithm select
	 * neighbouring nodes and decides there order based on the number of edge
	 * crossings between them and those connected to them.
	 * 
	 * @author Adam Kovacs
	 * 
	 */
	public static class GreedyCrossingReducer implements CrossingReducer {
		private final Map<NodeLayout, NodeWrapper> map = new IdentityHashMap<NodeLayout, NodeWrapper>();
		private List<List<NodeWrapper>> layers = new ArrayList<List<NodeWrapper>>();
		private Map<Integer, Integer> crossesForLayers = new IdentityHashMap<Integer, Integer>();

		/**
		 * Filters the multiple connections from the two arrays.
		 * 
		 * @param a
		 * @param b
		 * @return
		 */
		private ArrayList<NodeLayout> unionOfNodes(NodeLayout[] a,
				NodeLayout[] b) {
			ArrayList<NodeLayout> res = new ArrayList<NodeLayout>();

			for (int i = 0; i < a.length; i++)
				res.add(a[i]);
			for (int i = 0; i < b.length; i++)
				if (!res.contains(b[i]))
					res.add(b[i]);

			return res;
		}

		/**
		 * Returns the number of crosses between the two nodes and those
		 * connected to them.
		 * 
		 * @param nodeA
		 * @param nodeB
		 * @return
		 */
		private int numberOfCrosses(NodeWrapper nodeA, NodeWrapper nodeB) {
			int numOfCrosses = 0;
			if (nodeA.equals(nodeB))
				return 0;

			// Filter nodes connected with bidirectional edges
			ArrayList<NodeLayout> adjacentNodesOfA = unionOfNodes(
					nodeA.node.getPredecessingNodes(),
					nodeA.node.getSuccessingNodes());
			ArrayList<NodeLayout> adjacentNodesOfB = unionOfNodes(
					nodeB.node.getPredecessingNodes(),
					nodeB.node.getSuccessingNodes());

			for (NodeLayout aNode : adjacentNodesOfA) {
				ArrayList<Integer> alreadyCrossed = new ArrayList<Integer>();
				NodeWrapper aNodeWrapper = map.get(aNode);
				for (int i = 0; i < adjacentNodesOfB.size(); i++) {
					NodeWrapper nw = map.get(adjacentNodesOfB.get(i));
					if (!alreadyCrossed.contains(i) && nw != null) {
						// only if on the same side
						if ((nw.layer > nodeA.layer && aNodeWrapper.layer > nodeA.layer)
								|| (nw.layer < nodeA.layer && aNodeWrapper.layer < nodeA.layer)) {
							if (nodeA.index < nodeB.index) {
								if (aNodeWrapper.index > nw.index) {
									numOfCrosses++;
									alreadyCrossed.add(i);
								} else if (nw.index == aNodeWrapper.index) {
									if (nodeA.index >= nw.index) {
										// implies nodeB.index > nw.index
										if ((aNodeWrapper.layer > nw.layer && nodeA.layer < nw.layer)
												|| (aNodeWrapper.layer < nw.layer && nw.layer < nodeA.layer)) {
											// top-left or bottom-left quarter
											numOfCrosses++;
											alreadyCrossed.add(i);
										}
									} else if (nodeB.index <= nw.index) {
										// implies nodeA.index < nw.index
										if ((aNodeWrapper.layer > nw.layer && aNodeWrapper.layer < nodeB.layer)
												|| (aNodeWrapper.layer < nw.layer && aNodeWrapper.layer > nodeB.layer)) {
											// top-right or bottom-right quarter
											numOfCrosses++;
											alreadyCrossed.add(i);
										}
									}
								}
							} else if (nodeA.index > nodeB.index) {
								if (aNodeWrapper.index < nw.index) {
									numOfCrosses++;
									alreadyCrossed.add(i);
								} else if (nw.index == aNodeWrapper.index) {
									if (nodeB.index >= nw.index) {
										// implies nodeB.index > nw.index
										if ((aNodeWrapper.layer > nw.layer && nodeB.layer > aNodeWrapper.layer)
												|| (aNodeWrapper.layer < nw.layer && aNodeWrapper.layer > nodeB.layer)) {
											// top-left or bottom-left quarter
											numOfCrosses++;
											alreadyCrossed.add(i);
										}
									} else if (nodeA.index <= nw.index) {
										// implies nodeA.index < nw.index
										if ((aNodeWrapper.layer > nw.layer && nw.layer > nodeA.layer)
												|| (aNodeWrapper.layer < nw.layer && nw.layer < nodeA.layer)) {
											// top-right or bottom-right quarter
											numOfCrosses++;
											alreadyCrossed.add(i);
										}
									}
								}
							}
						}
					}
				}
			}

			return numOfCrosses;
		}

		/**
		 * Iterates the list and switches that results in less crossings.
		 * 
		 * @param layer
		 * @return
		 */
		private boolean greedyHeuristic(List<NodeWrapper> layer) {
			boolean res = false;
			if (layer.size() > 1) {
				for (int i = 0; i < layer.size() - 1; i++) {
					if (layer.get(i).node != null
							&& layer.get(i + 1).node != null) {
						int num1 = numberOfCrosses(layer.get(i),
								layer.get(i + 1));
						int num2 = numberOfCrosses(layer.get(i + 1),
								layer.get(i));
						if (num1 > num2 || (num1 == num2 && num1 > 0)) {
							if (!crossesForLayers
									.containsKey((layer.get(i).layer))
									|| crossesForLayers.get(layer.get(i).layer) > num2) {
								crossesForLayers.put(layer.get(i).layer, num2);
								res = true;
								int level = layer.get(0).layer;

								NodeWrapper tmp = layers.get(level).get(i);
								int tmpindex = layers.get(level).get(i).index;
								layers.get(level).get(i).index = layers.get(
										level).get(i + 1).index;
								layers.get(level).set(i,
										layers.get(level).get(i + 1));
								layers.get(level).get(i + 1).index = tmpindex;
								layers.get(level).set(i + 1, tmp);
							}
						}
					}
				}
			}
			return res;
		}

		public void crossReduction(List<List<NodeWrapper>> nodes) {
			crossesForLayers.clear();
			layers = nodes;

			// Builds the map
			for (List<NodeWrapper> layer : nodes)
				for (NodeWrapper node : layer)
					map.put(node.node, node);

			// After three iteration with no change it stops
			int iteration = 0;
			boolean change = false;
			while (iteration < 3) {
				change = false;
				for (int i = 0; i < nodes.size(); i++) {
					if (greedyHeuristic(layers.get(i))) {
						change = true;
					}
				}
				if (!change)
					iteration++;
			}
		}
	}

	/**
	 * Structure to store nodes and there positions in the layers. Furthermore
	 * predecessors and successors can be assigned to the nodes.
	 * 
	 * @author Adam Kovacs
	 * 
	 */
	public static class NodeWrapper {
		int index;
		final int layer;
		final NodeLayout node;
		final List<NodeWrapper> pred = new LinkedList<NodeWrapper>();
		final List<NodeWrapper> succ = new LinkedList<NodeWrapper>();
		private static final int PADDING = -1;

		NodeWrapper(NodeLayout n, int l) {
			node = n;
			layer = l;
		} // NodeLayout wrapper

		NodeWrapper(int l) {
			this(null, l);
		} // Dummy to connect two NodeLayout objects

		NodeWrapper() {
			this(null, PADDING);
		} // Padding for final refinement phase

		void addPredecessor(NodeWrapper node) {
			pred.add(node);
		}

		void addSuccessor(NodeWrapper node) {
			succ.add(node);
		}

		boolean isDummy() {
			return ((node == null) && (layer != PADDING));
		}

		boolean isPadding() {
			return ((node == null) && (layer == PADDING));
		}

		int getBaryCenter(List<NodeWrapper> list) {
			if (list.isEmpty())
				return (this.index);
			if (list.size() == 1)
				return (list.get(0).index);
			double barycenter = 0;
			for (NodeWrapper node : list)
				barycenter += node.index;
			return ((int) (barycenter / list.size())); // always rounding off to
														// avoid wrap around in
														// position refining!!!
		}

		int getPriorityDown() {
			if (isPadding())
				return (0);
			if (isDummy()) {
				if (succ != null && succ.size() > 0) {
					if (succ.get(0).isDummy())
						return (Integer.MAX_VALUE); // part of a straight line
					else
						return (Integer.MAX_VALUE >> 1); // start of a straight
															// line
				}
			}
			return (pred.size());
		}

		int getPriorityUp() {
			if (isPadding())
				return (0);
			if (isDummy()) {
				if (pred != null && pred.size() > 0) {
					if (pred.get(0).isDummy())
						return (Integer.MAX_VALUE); // part of a straight line
					else
						return (Integer.MAX_VALUE >> 1); // start of a straight
															// line
				}
			}
			return (succ.size());
		}

	}

	/**
	 * 
	 * An interface for creating layers. Interface for parameterizable layering
	 * heuristics.
	 * 
	 * @author Adam Kovacs
	 */
	public static interface LayerProvider {

		/**
		 * Creating layers of the nodes and makes it possible to assign layers
		 * to those nodes.
		 * 
		 * @param nodes
		 *            List of all the nodes that needs to be organized
		 * @return a list of layers for the given nodes, represented each as a
		 *         list of {@link NodeWrapper}s
		 */
		List<List<NodeWrapper>> calculateLayers(List<NodeLayout> nodes);
	}

	/**
	 * Processing the nodes based on depth first search and creating a list of
	 * layers
	 * 
	 * @author Adam Kovacs
	 * 
	 */
	public static class DFSLayerProvider implements LayerProvider {

		private Map<NodeLayout, Integer> assignedNodes = new IdentityHashMap<NodeLayout, Integer>();

		/**
		 * Returns the mutual connections of the two array given as parameters.
		 * 
		 * @param a
		 * @param b
		 * @return
		 */
		private List<ConnectionLayout> intersectOfConnections(
				ConnectionLayout[] a, ConnectionLayout[] b) {
			ArrayList<ConnectionLayout> res = new ArrayList<ConnectionLayout>();

			for (int i = 0; i < a.length; i++)
				for (int j = 0; j < b.length; j++)
					if (a[i].equals(b[j]))
						res.add(a[i]);

			return res;
		}

		private void addToInitClosedList(NodeLayout node, int layout,
				List<NodeLayout> initClosedList,
				Map<NodeLayout, NodeWrapper> map) {
			NodeWrapper nw = new NodeWrapper(node, layout);
			map.put(node, nw);
			initClosedList.add(node);
		}

		/**
		 * Finds the root elements in the list of nodes based on their
		 * connections.
		 * 
		 * @param nodes
		 * @return the list of root elements
		 */
		public ArrayList<NodeLayout> getRoots(List<NodeLayout> nodes) {
			ArrayList<NodeLayout> res = new ArrayList<NodeLayout>();

			for (NodeLayout node : nodes) {
				// directed edges
				if (node.getIncomingConnections().length == 0)
					res.add(node);
				else {
					int sizeOfIntersect = intersectOfConnections(
							node.getIncomingConnections(),
							node.getOutgoingConnections()).size();
					// there are more outgoing edges, besides the bidirectionals
					if (node.getOutgoingConnections().length > sizeOfIntersect)
						res.add(node);
					// only bidirectional edges, no incoming directed edges
					if (node.getIncomingConnections().length == sizeOfIntersect
							&& node.getOutgoingConnections().length == sizeOfIntersect)
						res.add(node);
				}
			}

			// if no sources then we only have bidirectional edges and/or cycles
			if (res.size() == 0)
				res.add(nodes.get(0));

			return res;
		}

		public Map<NodeLayout, Integer> getAssignedNodes() {
			return assignedNodes;
		}

		public void addAssignedNode(NodeLayout node, int layer) {
			assignedNodes.put(node, layer);
		}

		public void clearAssignedNodes() {
			assignedNodes.clear();
		}

		private static void updateIndex(List<NodeWrapper> list) {
			for (int index = 0; index < list.size(); index++)
				list.get(index).index = index;
		}

		/**
		 * Creates a new layer and puts the elements of this layer to the map.
		 * 
		 * @param list
		 */
		private void addLayer(List<NodeLayout> list,
				List<List<NodeWrapper>> layers, Map<NodeLayout, NodeWrapper> map) {
			ArrayList<NodeWrapper> layer = new ArrayList<NodeWrapper>(
					list.size());
			for (NodeLayout node : list) {
				// wrap each NodeLayout with the internal data object and
				// provide a
				// corresponding mapping
				NodeWrapper nw = new NodeWrapper(node, layers.size());
				map.put(node, nw);
				layer.add(nw);
			}
			layers.add(layer);
			updateIndex(layer);
		}

		/**
		 * Finds the connected nodes to be processed.
		 * 
		 * @param toUnfold
		 * @return
		 */
		private ArrayList<NodeLayout> Unfold(NodeLayout toUnfold,
				Set<NodeLayout> openedList, Set<NodeLayout> closedList) {
			ArrayList<NodeLayout> res = new ArrayList<NodeLayout>();

			for (int i = 0; i < toUnfold.getOutgoingConnections().length; i++) {
				NodeLayout endPoint = toUnfold.getOutgoingConnections()[i]
						.getTarget();
				if (endPoint.equals(toUnfold))
					endPoint = toUnfold.getOutgoingConnections()[i].getSource();
				if (!closedList.contains(endPoint)
						&& !openedList.contains(endPoint)
						&& !res.contains(endPoint))
					res.add(endPoint);
			}
			for (int i = 0; i < toUnfold.getIncomingConnections().length; i++) {
				NodeLayout endPoint = toUnfold.getIncomingConnections()[i]
						.getTarget();
				if (endPoint.equals(toUnfold))
					endPoint = toUnfold.getIncomingConnections()[i].getSource();
				if (!closedList.contains(endPoint)
						&& !openedList.contains(endPoint)
						&& !res.contains(endPoint))
					res.add(endPoint);
			}

			return res;
		}

		public List<List<NodeWrapper>> calculateLayers(
				List<NodeLayout> nodeLayouts) {
			List<NodeLayout> nodes = new ArrayList<NodeLayout>(nodeLayouts);
			Set<NodeLayout> openedList = new HashSet<NodeLayout>();
			List<NodeLayout> initClosedList = new ArrayList<NodeLayout>();
			Set<NodeLayout> closedList = new HashSet<NodeLayout>();
			List<List<NodeWrapper>> layers = new ArrayList<List<NodeWrapper>>();
			Map<NodeLayout, NodeWrapper> map = new IdentityHashMap<NodeLayout, NodeWrapper>();

			// Assigns the given nodes to there layers
			if (assignedNodes.size() > 0) {
				for (NodeLayout node : nodes) {
					if (assignedNodes.containsKey(node))
						addToInitClosedList(node, assignedNodes.get(node),
								initClosedList, map);
				}
			}

			// Only at first iteration, clearing initClosedList, starting to
			// build
			// layers
			if (initClosedList.size() > 0) {
				closedList.addAll(initClosedList);
				nodes.removeAll(initClosedList);
				initClosedList.clear();

				for (NodeLayout node : closedList) {
					if (map.get(node).layer < layers.size()) {
						layers.get(map.get(node).layer).add(map.get(node));
						updateIndex(layers.get(map.get(node).layer));
					} else {
						while (map.get(node).layer != layers.size()) {
							ArrayList<NodeLayout> layer = new ArrayList<NodeLayout>();
							addLayer(layer, layers, map);
						}
						ArrayList<NodeLayout> layer = new ArrayList<NodeLayout>();
						layer.add(node);
						addLayer(layer, layers, map);
					}
				}
			}

			ArrayList<NodeLayout> startPoints = new ArrayList<NodeLayout>();
			// Starts by finding a root or selecting the first from the assigned
			// ones
			if (layers.size() > 0 && layers.get(0).size() > 0)
				startPoints.add(layers.get(0).get(0).node);
			else if (layers.size() == 0) {
				startPoints.add(getRoots(nodes).get(0));
				addLayer(startPoints, layers, map);
			} else {
				startPoints.add(getRoots(nodes).get(0));
				for (NodeLayout startPoint : startPoints) {
					if (!map.containsKey(startPoint)) {
						NodeWrapper nw = new NodeWrapper(startPoint, 0);
						map.put(startPoint, nw);
						layers.get(0).add(nw);
					}
				}
				updateIndex(layers.get(0));
			}
			openedList.addAll(startPoints);
			NodeLayout toUnfold = startPoints.get(0);

			while (nodes.size() > 0) {
				// while openedList isn't empty it searches for further nodes
				// and
				// adding them to the next layer
				while (openedList.size() != 0) {
					ArrayList<NodeLayout> unfolded = Unfold(toUnfold,
							openedList, closedList);
					if (unfolded.size() > 0) {
						int level = map.get(toUnfold).layer + 1;
						if (level < layers.size()) {
							for (NodeLayout n : unfolded) {
								if (!map.containsKey(n)) {
									NodeWrapper nw = new NodeWrapper(n, level);
									map.put(n, nw);
									layers.get(level).add(nw);
								}
							}
							updateIndex(layers.get(level));
						} else {
							ArrayList<NodeLayout> layer = new ArrayList<NodeLayout>();
							layer.addAll(unfolded);
							addLayer(layer, layers, map);
						}
						openedList.addAll(unfolded);
					}
					closedList.add(toUnfold);
					openedList.remove(toUnfold);
					nodes.remove(toUnfold);

					if (openedList.size() != 0)
						toUnfold = openedList.iterator().next();
				}
				if (nodes.size() > 0) {
					final NodeLayout node = nodes.get(0);
					openedList.add(node);
					NodeWrapper nw = new NodeWrapper(node, 0);
					map.put(node, nw);
					layers.get(0).add(nw);
				}
			}
			return layers;
		}
	}

	/**
	 * 
	 * @author Rene Kuhlemann
	 *
	 */
	public static class SimpleLayerProvider implements LayerProvider {

		private static final int MAX_LAYERS = 10;
		private final List<List<NodeWrapper>> layers = new ArrayList<List<NodeWrapper>>(
				MAX_LAYERS);
		private final Map<NodeLayout, NodeWrapper> map = new IdentityHashMap<NodeLayout, NodeWrapper>();

		private static List<NodeLayout> findRoots(List<NodeLayout> list) {
			List<NodeLayout> roots = new ArrayList<NodeLayout>();
			for (NodeLayout iter : list) { // no predecessors means: this is a
											// root,
											// add it to list
				if (iter.getPredecessingNodes().length == 0)
					roots.add(iter);
			}
			return (roots);
		}

		/**
		 * Wraps all {@link NodeLayout} objects into an internal presentation
		 * {@link NodeWrapper} and inserts dummy wrappers into the layers
		 * between an object and their predecessing nodes if necessary. Finally,
		 * all nodes are chained over immediate adjacent layers down to their
		 * predecessors. This is necessary to apply the final step of the
		 * Sugiyama algorithm to refine the node position within a layer.
		 * 
		 * @param list
		 *            : List of all {@link NodeLayout} objects within the
		 *            current layer
		 */
		private void addLayer(List<NodeLayout> list) {
			ArrayList<NodeWrapper> layer = new ArrayList<NodeWrapper>(
					list.size());
			for (NodeLayout node : list) {
				// wrap each NodeLayout with the internal data object and
				// provide a
				// corresponding mapping
				NodeWrapper nw = new NodeWrapper(node, layers.size());
				map.put(node, nw);
				layer.add(nw);
				// insert dummy nodes if the adjacent layer does not contain the
				// predecessor
				for (NodeLayout node_predecessor : node.getPredecessingNodes()) { // for
																					// all
																					// predecessors
					NodeWrapper nw_predecessor = map.get(node_predecessor);
					if (nw_predecessor != null) {
						for (int level = nw_predecessor.layer + 1; level < nw.layer; level++) {
							// add "virtual" wrappers (dummies) to the layers in
							// between
							// virtual wrappers are in fact parts of a double
							// linked
							// list
							NodeWrapper nw_dummy = new NodeWrapper(level);
							nw_dummy.addPredecessor(nw_predecessor);
							nw_predecessor.addSuccessor(nw_dummy);
							nw_predecessor = nw_dummy;
							layers.get(level).add(nw_dummy);
						}
						nw.addPredecessor(nw_predecessor);
						nw_predecessor.addSuccessor(nw);
					}
				}
			}
			layers.add(layer);
			updateIndex(layer);
		}

		private static void updateIndex(List<NodeWrapper> list) {
			for (int index = 0; index < list.size(); index++)
				list.get(index).index = index;
		}

		public List<List<NodeWrapper>> calculateLayers(List<NodeLayout> nodes) {
			map.clear();

			List<NodeLayout> predecessors = findRoots(nodes);
			nodes.removeAll(predecessors); // nodes now contains only nodes that
											// are
											// no roots
			addLayer(predecessors);
			for (int level = 1; nodes.isEmpty() == false; level++) {
				if (level > MAX_LAYERS)
					throw new RuntimeException(
							"Graphical tree exceeds maximum depth of "
									+ MAX_LAYERS
									+ "! (Graph not directed? Cycles?)");
				List<NodeLayout> layer = new ArrayList<NodeLayout>();
				for (NodeLayout item : nodes) {
					if (predecessors.containsAll(Arrays.asList(item
							.getPredecessingNodes())))
						layer.add(item);
				}
				if (layer.size() == 0)
					layer.add(nodes.get(0));
				nodes.removeAll(layer);
				predecessors.addAll(layer);
				addLayer(layer);
			}

			return layers;
		}

	}

	private List<List<NodeWrapper>> layers = new ArrayList<List<NodeWrapper>>();
	private Map<NodeLayout, NodeWrapper> map = new IdentityHashMap<NodeLayout, NodeWrapper>();
	private final Direction direction;
	private final Dimension dimension;

	private LayoutContext context;
	private int last; // index of the last element in a layer after padding
						// process

	private LayerProvider layerProvider;
	private CrossingReducer crossingReducer;

	/**
	 * Constructs a tree-like, layered layout of a directed graph.
	 * 
	 * @param dir
	 *            {@link Direction#HORIZONTAL}: left to right -
	 *            {@link Direction#VERTICAL} : top to bottom
	 * 
	 * @param dim
	 *            - desired size of the layout area. Uses the BOUNDS_PROPERTY of
	 *            the LayoutContext if not set
	 * 
	 * @param layering
	 *            - implementation of LayerProvider interface
	 * 
	 * @param crossing
	 *            - implementation of CrossingReducer interface
	 */
	public SugiyamaLayoutAlgorithm(Direction dir, Dimension dim,
			LayerProvider layering, CrossingReducer crossing) {
		direction = dir;
		dimension = dim;

		layerProvider = (layering == null) ? new SimpleLayerProvider()
				: layering;
		crossingReducer = (crossing == null) ? new BarycentricCrossingReducer()
				: crossing;
	}

	public SugiyamaLayoutAlgorithm(Direction dir, LayerProvider layerProvider,
			CrossingReducer crossing) {
		this(dir, null, layerProvider, crossing);
	}

	public SugiyamaLayoutAlgorithm(Direction dir, LayerProvider layerProvider) {
		this(dir, null, layerProvider, new BarycentricCrossingReducer());
	}

	public SugiyamaLayoutAlgorithm(Direction dir, CrossingReducer crossing) {
		this(dir, null, null, crossing);
	}

	public SugiyamaLayoutAlgorithm(Direction dir, Dimension dim) {
		this(dir, dim, null, null);
	}

	public SugiyamaLayoutAlgorithm(Direction dir) {
		this(dir, null, null, null);
	}

	public SugiyamaLayoutAlgorithm() {
		this(Direction.VERTICAL, null, null, null);
	}

	public void setLayoutContext(LayoutContext context) {
		this.context = context;
	}

	public LayoutContext getLayoutContext() {
		return context;
	}

	public void applyLayout(boolean clean) {
		if (!clean)
			return;
		layers.clear();
		map.clear();

		ArrayList<NodeLayout> nodes = new ArrayList<NodeLayout>();
		ArrayList<NodeLayout> nodes2 = new ArrayList<NodeLayout>();
		for (NodeLayout node : context.getNodes()) {
			nodes.add(node);
			nodes2.add(node);
		}
		layers = layerProvider.calculateLayers(nodes);
		crossingReducer.crossReduction(layers);

		for (List<NodeWrapper> layer : layers) {
			if (layer.size() > last)
				last = layer.size();
			for (NodeWrapper nw : layer) {
				map.put(nw.node, nw);
			}
		}
		calculatePositions();
	}

	private void calculatePositions() {
		Rectangle boundary = LayoutPropertiesHelper.getBounds(context);
		if (dimension != null)
			boundary = new Rectangle(0, 0, dimension.getWidth(),
					dimension.getHeight());
		double dx = boundary.getWidth() / layers.size();
		double dy = boundary.getHeight() / (last + 1);
		if (direction == Direction.HORIZONTAL)
			for (NodeLayout node : context.getNodes()) {
				NodeWrapper nw = map.get(node);
				LayoutPropertiesHelper.setLocation(node,
						(nw.layer + 0.5d) * dx, (nw.index + 0.5d) * dy);
			}
		else
			for (NodeLayout node : context.getNodes()) {
				NodeWrapper nw = map.get(node);
				LayoutPropertiesHelper.setLocation(node,
						(nw.index + 0.5d) * dx, (nw.layer + 0.5d) * dy);
			}
	}

}