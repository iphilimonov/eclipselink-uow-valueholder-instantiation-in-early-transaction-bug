# EclipseLink UoW ValueHolder Instantiation Bug

When indirected relationships are triggered on UnitOfWork clones after `beginEarlyTransaction()`, the loaded objects are not registered in `cloneToOriginals`. This fractures the clone graph — back-reference navigation returns different object instances, and modifications to those instances are silently lost on commit. With `ServerSession` and connection pooling, the untracked objects trigger queries on a different database connection than the one holding the transaction locks, causing deadlocks.

**Version:** EclipseLink 3.0.3

## How to Run

```
mvn compile exec:java -Dexec.mainClass=demo.ReproduceBug
```

Or open in IntelliJ and run `ReproduceBug.main()`.

## Expected Output

```
=== Back-Reference Identity ===
Parent1 : true
Parent2 : true
Parent3 : true

=== Clone Tracking (size=6, expected 6=3 parents + 3 children) ===
Parent1 : true
Parent2 : true
Parent3 : true
Child1 : true
Child2 : true
Child3 : true

=== Post-Commit Children Changes Verification ===
1 : ?
2 : ?
3 : ?
```

## Actual Output

```
=== Back-Reference Identity ===
Parent1 : true
Parent2 : false
Parent3 : false

=== Clone Tracking (size=4, expected 6=3 parents + 3 children) ===
Parent1 : true
Parent2 : false
Parent3 : false
Child1 : true
Child2 : false
Child3 : false

=== Post-Commit Children Changes Verification ===
1 : ?
2 : ChildValue2
3 : ChildValue3
```

Only Parent1 (whose children were pre-triggered before `beginEarlyTransaction()`) works correctly. Parent2 and Parent3 have fractured clone graphs, and their modifications are silently lost.
