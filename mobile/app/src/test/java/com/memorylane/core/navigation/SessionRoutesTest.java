package com.memorylane.core.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.memorylane.R;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

public class SessionRoutesTest {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String AUTH_SCREENS = "com.memorylane.feature.auth.ui.";

    @Test
    public void authScreensArePublic() {
        for (int id : new int[] {R.id.loginFragment, R.id.registerFragment}) {
            assertTrue(SessionRoutes.isPublic(id));
            assertFalse(SessionRoutes.requiresSession(id));
        }
    }

    @Test
    public void tabsRequireSession() {
        for (int id : new int[] {R.id.memoriesFragment, R.id.mapFragment, R.id.profileFragment}) {
            assertTrue(SessionRoutes.requiresSession(id));
            assertFalse(SessionRoutes.isPublic(id));
        }
    }

    @Test
    public void unknownDestinationRequiresSession() {
        assertTrue(SessionRoutes.requiresSession(0));
        assertTrue(SessionRoutes.requiresSession(-1));
        assertTrue(SessionRoutes.requiresSession(R.id.nav_graph));
    }

    /**
     * Ties the list to nav_graph.xml: auth screens (login, register) must be public, every
     * other destination protected. A new auth screen left out of SessionRoutes fails here instead of
     * silently bouncing to login on the device.
     */
    @Test
    public void everyGraphDestinationIsClassifiedByItsScreen() throws Exception {
        File graph = new File("src/main/res/navigation/nav_graph.xml");
        assertTrue("run from the app module: " + graph.getAbsolutePath(), graph.isFile());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList fragments = factory.newDocumentBuilder().parse(graph).getElementsByTagName("fragment");

        List<String> checked = new ArrayList<>();
        for (int i = 0; i < fragments.getLength(); i++) {
            Element fragment = (Element) fragments.item(i);
            String idName = fragment.getAttributeNS(ANDROID_NS, "id").replace("@+id/", "").replace("@id/", "");
            String screen = fragment.getAttributeNS(ANDROID_NS, "name");
            int id = R.id.class.getField(idName).getInt(null);
            boolean authScreen = screen.startsWith(AUTH_SCREENS);
            assertEquals(idName + " (" + screen + ")", authScreen, SessionRoutes.isPublic(id));
            checked.add(idName);
        }
        assertTrue("nav_graph.xml has no login destination: " + checked, checked.contains("loginFragment"));
        assertTrue("nav_graph.xml has no register destination: " + checked, checked.contains("registerFragment"));
        assertTrue("nav_graph.xml has no protected destination: " + checked, checked.size() > 1);
    }
}
