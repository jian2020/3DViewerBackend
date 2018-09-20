package org.bimserver.geometry.accellerator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.bimserver.database.queries.Bounds;
import org.bimserver.database.queries.ObjectWrapper;

public class Node<V extends Comparable<V>> {
	private final Node<V>[] nodes = new Node[8];
	private Bounds bounds;
	private List<ObjectWrapper<V>> values = new ArrayList<>();
	private int level;
	private int id;
	private int deepestLevel;
	private int maxDepth;
	private Octree<V> root;
	
	public Node(Octree<V> root, Bounds bounds, int level, int parentId, int localId, int maxDepth) {
		this.id = parentId * 8 + localId;
		if (root != null) {
			root.addToList(this);
		}
		this.root = root;
		this.bounds = bounds;
		this.level = level;
		this.maxDepth = maxDepth;
	}
	
	public int size() {
		int total = 0;
		for (Node<V> node : nodes) {
			if (node != null) {
				total += node.size();
			}
		}
		total += values.size();
		return total;
	}
	
	protected void setRoot(Octree<V> root) {
		this.root = root;
	}
	
	public int getId() {
		return id;
	}
	
	public Collection<ObjectWrapper<V>> getValues() {
		return values;
	}

	public int add(V v, Bounds bounds) {
		if (level != maxDepth) {
			int localId = 1;
			for (int x=0; x<=1; x++) {
				for (int y=0; y<=1; y++) {
					for (int z=0; z<=1; z++) {
						Bounds offset = this.bounds.offset(x, y, z);
						if (bounds.within(offset)) {
							Node<V> node = nodes[x * 4 + y * 2 + z];
							if (node == null) {
								node = new Node<>(root, offset, this.level + 1, this.id, localId, maxDepth);
								nodes[x * 4 + y * 2 + z] = node;
							}
							int finalLevel = node.add(v, bounds);
							if (finalLevel > deepestLevel) {
								deepestLevel = finalLevel;
							}
							return finalLevel;
						}
						localId++;
					}
				}
			}
		}
		// Did not fit in any of the children
//		System.out.println(q++ + ", " + this.level);
		if (!values.add(new ObjectWrapper<>(bounds, v))) {
		}
		return level;
	}
	
	public int getDeepestLevel() {
		return deepestLevel;
	}
	
	public void query(List<V> results, Bounds bounds) {
		for (Node<V> node : nodes) {
			if (node != null) {
				if (bounds.getMinX() > node.getBounds().getMaxX() || bounds.getMinY() > node.getBounds().getMaxY() || bounds.getMinZ() > node.getBounds().getMaxZ()) {
					continue;
				}
				if (bounds.getMaxX() < node.getBounds().getMinX() || bounds.getMaxY() < node.getBounds().getMinY() || bounds.getMaxZ() < node.getBounds().getMinZ()) {
					continue;
				}
				node.query(results, bounds);
			}
		}
	}

	private Bounds getBounds() {
		return bounds;
	}

	public int getNrObjects() {
		return values.size();
	}
	
	public Node<V>[] getNodes() {
		return nodes;
	}
	
	public void traverseBreathFirst(Traverser<V> traverser, int level) {
		if (this.level == level) {
			traverser.traverse(this);
		}
		if (level < this.level) {
			return;
		}
		for (Node<V> child : nodes) {
			if (child != null) {
				child.traverseBreathFirst(traverser, level);
			}
		}
	}

	public void sort() {
		Collections.sort(this.values);
	}
}