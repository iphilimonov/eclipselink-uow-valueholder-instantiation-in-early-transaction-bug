package demo;

import org.eclipse.persistence.indirection.ValueHolder;
import org.eclipse.persistence.indirection.ValueHolderInterface;

public class Child {

    private Long id;
    private String value;
    private ValueHolderInterface parent = new ValueHolder();

    public Child() {}

    public Child(String value) {
        this.value = value;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public Parent getParent() { return (Parent) parent.getValue(); }
    public void setParent(Parent parent) { this.parent.setValue(parent); }

}
