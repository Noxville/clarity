package skadistats.clarity.decoder.s2;

public class HuffmanGraph {

    private final HuffmanTree.Node root;
    private final StringBuilder g;

    public HuffmanGraph(HuffmanTree.Node root) {
        this.root = root;
        this.g = new StringBuilder();
    }

    public String generate() {
        g.append("digraph G {\n");
        g.append("graph [ranksep=0];\n");
        genNodesRecursive(root, "");
        genEdgesRecursive(root);
        g.append("}");
        return g.toString();
    }

    public void genNodesRecursive(HuffmanTree.Node node, String path) {
        if (node instanceof HuffmanTree.InternalNode) {
            HuffmanTree.InternalNode internal = (HuffmanTree.InternalNode) node;
            g.append(String.format("%s [label=%s];\n", internal.getNum(), internal.getWeight()));
            if (internal.getLeft() != null)
                genNodesRecursive(internal.getLeft(), path + "0");
            if (internal.getRight() != null)
                genNodesRecursive(internal.getRight(), path + "1");
        } else {
            HuffmanTree.LeafNode leaf = (HuffmanTree.LeafNode) node;
            g.append(String.format("%s [shape=record, label=\"{{%s|%s}|%s}\"];\n", leaf.getNum(), path, leaf.getWeight(), leaf.getOp().toString()));
        }
    }

    public void genEdgesRecursive(HuffmanTree.Node node) {
        if (node instanceof HuffmanTree.InternalNode) {
            HuffmanTree.InternalNode internal = (HuffmanTree.InternalNode) node;
            if (internal.getLeft() != null) {
                g.append(String.format("%s -> %s [label=0];\n", internal.getNum(), internal.getLeft().getNum()));
                genEdgesRecursive(internal.getLeft());
            }
            if (internal.getRight() != null) {
                g.append(String.format("%s -> %s [label=1];\n", internal.getNum(), internal.getRight().getNum()));
                genEdgesRecursive(internal.getRight());
            }
        }
    }


}
