package demo;

import org.eclipse.persistence.descriptors.RelationalDescriptor;
import org.eclipse.persistence.internal.sessions.UnitOfWorkImpl;
import org.eclipse.persistence.mappings.DirectToFieldMapping;
import org.eclipse.persistence.mappings.OneToManyMapping;
import org.eclipse.persistence.mappings.OneToOneMapping;
import org.eclipse.persistence.platform.database.H2Platform;
import org.eclipse.persistence.sessions.DatabaseLogin;
import org.eclipse.persistence.sessions.DatabaseSession;
import org.eclipse.persistence.sessions.Project;
import org.eclipse.persistence.sessions.UnitOfWork;

import java.util.List;
import java.util.Map;

public class ReproduceBug {

    public static void main(String[] args) {
        DatabaseSession session = buildSession();
        session.login();

        try {
            insertTestData(session);
            reproduceBug(session);
        } finally {
            session.logout();
        }
    }

    private static DatabaseSession buildSession() {
        Project project = new Project();

        // Database login
        DatabaseLogin login = new DatabaseLogin();
        login.setPlatform(new H2Platform());
        login.setDriverClassName("org.h2.Driver");
        login.setConnectionString("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        login.setUserName("sa");
        login.setPassword("");
        login.setDefaultSequence(new org.eclipse.persistence.sequencing.NativeSequence("", 1, false));
        project.setLogin(login);

        // Parent descriptor
        RelationalDescriptor parentDesc = new RelationalDescriptor();
        parentDesc.setJavaClass(Parent.class);
        parentDesc.setTableName("PARENT");
        parentDesc.addPrimaryKeyFieldName("ID");
        parentDesc.setSequenceNumberFieldName("ID");
        parentDesc.setSequenceNumberName("PARENT_SEQ");
        parentDesc.useNoIdentityMap();
        parentDesc.getQueryManager().checkDatabaseForDoesExist();

        DirectToFieldMapping parentIdMapping = new DirectToFieldMapping();
        parentIdMapping.setAttributeName("id");
        parentIdMapping.setFieldName("ID");
        parentDesc.addMapping(parentIdMapping);

        DirectToFieldMapping parentNameMapping = new DirectToFieldMapping();
        parentNameMapping.setAttributeName("name");
        parentNameMapping.setFieldName("NAME");
        parentDesc.addMapping(parentNameMapping);

        OneToManyMapping childrenMapping = new OneToManyMapping();
        childrenMapping.setAttributeName("children");
        childrenMapping.setReferenceClass(Child.class);
        childrenMapping.addTargetForeignKeyFieldName("CHILD.PARENT_ID", "PARENT.ID");
        childrenMapping.useBatchReading();
        childrenMapping.privateOwnedRelationship();
        childrenMapping.useTransparentCollection();
        parentDesc.addMapping(childrenMapping);

        // Child descriptor
        RelationalDescriptor childDesc = new RelationalDescriptor();
        childDesc.setJavaClass(Child.class);
        childDesc.setTableName("CHILD");
        childDesc.addPrimaryKeyFieldName("ID");
        childDesc.setSequenceNumberFieldName("ID");
        childDesc.setSequenceNumberName("CHILD_SEQ");
        childDesc.useNoIdentityMap();
        childDesc.getQueryManager().checkDatabaseForDoesExist();

        DirectToFieldMapping childIdMapping = new DirectToFieldMapping();
        childIdMapping.setAttributeName("id");
        childIdMapping.setFieldName("ID");
        childDesc.addMapping(childIdMapping);

        DirectToFieldMapping childValueMapping = new DirectToFieldMapping();
        childValueMapping.setAttributeName("value");
        childValueMapping.setFieldName("VAL");
        childDesc.addMapping(childValueMapping);

        OneToOneMapping parentMapping = new OneToOneMapping();
        parentMapping.setAttributeName("parent");
        parentMapping.setReferenceClass(Parent.class);
        parentMapping.addForeignKeyFieldName("PARENT_ID", "PARENT.ID");
        parentMapping.useBasicIndirection();
        childDesc.addMapping(parentMapping);

        project.addDescriptor(parentDesc);
        project.addDescriptor(childDesc);

        DatabaseSession session = project.createDatabaseSession();
        session.setLogLevel(java.util.logging.Level.FINE.intValue());
        return session;
    }

    private static void insertTestData(DatabaseSession session) {
        session.executeNonSelectingSQL("CREATE SEQUENCE IF NOT EXISTS PARENT_SEQ START WITH 1 INCREMENT BY 1");
        session.executeNonSelectingSQL("CREATE SEQUENCE IF NOT EXISTS CHILD_SEQ START WITH 1 INCREMENT BY 1");
        session.executeNonSelectingSQL("CREATE TABLE IF NOT EXISTS PARENT (ID BIGINT PRIMARY KEY, NAME VARCHAR(255))");
        session.executeNonSelectingSQL("CREATE TABLE IF NOT EXISTS CHILD (ID BIGINT PRIMARY KEY, VAL VARCHAR(255), PARENT_ID BIGINT REFERENCES PARENT(ID))");

        session.executeNonSelectingSQL("INSERT INTO PARENT (ID, NAME) VALUES (1, 'Parent1')");
        session.executeNonSelectingSQL("INSERT INTO PARENT (ID, NAME) VALUES (2, 'Parent2')");
        session.executeNonSelectingSQL("INSERT INTO PARENT (ID, NAME) VALUES (3, 'Parent3')");
        session.executeNonSelectingSQL("INSERT INTO CHILD (ID, VAL, PARENT_ID) VALUES (1, 'ChildValue1', 1)");
        session.executeNonSelectingSQL("INSERT INTO CHILD (ID, VAL, PARENT_ID) VALUES (2, 'ChildValue2', 2)");
        session.executeNonSelectingSQL("INSERT INTO CHILD (ID, VAL, PARENT_ID) VALUES (3, 'ChildValue3', 3)");
    }

    private static void reproduceBug(DatabaseSession session) {
        List<Parent> parents = (List<Parent>) session.readAllObjects(Parent.class);

        // Trigger lazy-load of children on ONLY the first parent.
        // This is the first step reproduce the bug.
        parents.get(0).getChildren().size();
        //uncomment these lines to "fix" the bug
        //parents.get(1).getChildren().size();
        //parents.get(2).getChildren().size();

        UnitOfWork uow = session.acquireUnitOfWork();
        try {
            List<Parent> clonedParents = (List<Parent>) uow.registerAllObjects(parents);
            // Force early transaction to pin uow Session to a single "physical" connection
            //comment this line to "fix" the bug
            uow.beginEarlyTransaction();
            uow.executeSQL("SELECT 1 FROM PARENT LIMIT 1 FOR UPDATE");

            for (Parent cloneParent : clonedParents) {
                for (Child clonedChild : cloneParent.getChildren()) {
                    clonedChild.getParent().getName(); // trigger back-reference instantiation
                }
            }

            for (Parent cloneParent : clonedParents) {
                // Navigate through back-references to prove the clone graph is fractured:
                // clonedChild.getParent().getChildren() may return DIFFERENT child instances
                // than cloneParent.getChildren() — changes to these orphans are silently lost.
                for (Child clonedChild : cloneParent.getChildren()) {
                    Child childViaBackRef = clonedChild.getParent().getChildren().get(0);
                    childViaBackRef.setValue("?");
                }
            }

            assertBackRefIdentity(clonedParents);
            assertCloneTracking(uow, clonedParents);
            uow.commit();
        } finally {
            uow.release();
        }

        System.out.println("\n=== Post-Commit Children Changes Verification ===");
        List<Child> children = (List<Child>) session.readAllObjects(Child.class);
        for (Child child : children) {
            System.out.printf("%s : %s%n", child.getId(), child.getValue());
        }
    }

    private static void assertBackRefIdentity(List<Parent> clonedParents) {
        System.out.println("\n=== Back-Reference Identity ===");
        for (Parent clone : clonedParents) {
            Parent backRef = clone.getChildren().get(0).getParent();
            System.out.printf("%s : %s%n", clone.getName(), clone == backRef);
        }
    }

    private static void assertCloneTracking(UnitOfWork uow, List<Parent> clonedParents) {
        Map cloneToOriginals = ((UnitOfWorkImpl) uow).getCloneToOriginals();
        System.out.printf("%n=== Clone Tracking (size=%d, expected 6=3 parents + 3 children) ===%n", cloneToOriginals.size());
        for (Parent clone : clonedParents) {
            Parent backRef = clone.getChildren().get(0).getParent();
            System.out.printf("%s : %s%n", clone.getName(), cloneToOriginals.containsKey(backRef));
        }
        for (Parent clonedParent : clonedParents) {
            Child clonedChild = clonedParent.getChildren().get(0);
            System.out.printf("%s : %s%n", clonedChild.getClass().getSimpleName() + clonedChild.getId(), cloneToOriginals.containsKey(clonedChild));
        }
    }
}
