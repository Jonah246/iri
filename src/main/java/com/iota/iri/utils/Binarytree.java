package com.iota.iri.utils;
import com.iota.iri.model.Hash;
public class Binarytree{
    public Hash value;
    private Binarytree leftChild;
    private Binarytree rightChild;
    public Binarytree(Hash value, Binarytree leftChild, Binarytree rightChild) {
        this.value = value;
        this.leftChild = leftChild;
        this.rightChild = rightChild;
    }
    public Binarytree(Hash value) {
        this(value, null, null);
    }
    public Hash getValue() {
        return value;
    }
    public Binarytree getLeftChild() {
        return leftChild;
    }
    public Binarytree getRightChild() {
        return rightChild;
    }
    public void setLeftChild(Binarytree subtree) throws IllegalArgumentException {
        if (contains(subtree, this)) {
            throw new IllegalArgumentException(
                "Subtree " + this +" already contains " + subtree);
        }
        leftChild = subtree;
    }
    public void setRightChild(Binarytree subtree) throws IllegalArgumentException {
        if (contains(subtree, this)) {
            throw new IllegalArgumentException(
                    "Subtree " + this +" already contains " + subtree);
        }
        rightChild = subtree;
    }

    public void setValue(Hash value) {
        this.value = value;
    }
    public boolean isLeaf() {
        return leftChild == null && rightChild == null;
    }
    

    protected boolean contains(Binarytree tree, Binarytree targetNode) {
        if (tree == null)
            return false;
        if (tree == targetNode)
            return true;
        return contains(targetNode, tree.getLeftChild())
            || contains(targetNode, tree.getRightChild());
    }
    
}
