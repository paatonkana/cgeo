package cgeo.geocaching;

import junit.framework.TestCase;

public class StoredListTest extends TestCase {

    public static void testStandardListExists() {
        final StoredList list = DataStore.getList(StoredList.STANDARD_LIST_ID);
        assertNotNull(list);
    }

    public static void testEquals() {
        final StoredList list1 = DataStore.getList(StoredList.STANDARD_LIST_ID);
        final StoredList list2 = DataStore.getList(StoredList.STANDARD_LIST_ID);
        assertEquals(list1, list2);
    }

}
