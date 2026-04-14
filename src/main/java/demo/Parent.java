package demo;

import java.util.ArrayList;
import java.util.List;

public class Parent {

    private Long id;
    private String name;
    private List<Child> children = new ArrayList<>();

    public Parent() {}

    public Parent(String name) {
        this.name = name;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<Child> getChildren() { return children; }
    public void setChildren(List<Child> children) { this.children = children; }

    public void addChild(Child child) {
        children.add(child);
        child.setParent(this);
    }

    @Override
    public String toString()
    {
        return id + " " + name;
    }

}
