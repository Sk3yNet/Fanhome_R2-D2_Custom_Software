package dev.r2hack.main;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * Off-robot tests for the Home Assistant activity select (the "liveliness" presets). No broker:
 * discovery, states and commands are checked directly. Run with apps/test.sh.
 */
public final class HomeAssistantTest {
    private static int passed, failed;

    static final class FakeHost implements HomeAssistant.Host {
        final JSONObject state = new JSONObject();
        final List<String> actions = new ArrayList<>();

        FakeHost() throws Exception {
            state.put("uuid", "ABCDEF12-3456").put("name", "R2-D2").put("version", "test").put("liveliness", "quiet");
        }

        public JSONObject haState() { return state; }
        public void action(String name) { actions.add(name); }
        public List<String> routineNames() { return Collections.emptyList(); }

        public List<String> placeNames() { return Collections.emptyList(); }

        public void goTo(String place) { }
        public void runRoutine(String name) { }
        public void projector(int mode) { }
        public void setVolume(int percent) { }
        public void watchUpdate(JSONObject msg) { }
        public void setWatch(boolean on) { }
        public void log(String msg) { }
        public void haStatus(String state) { }
    }

    public static void main(String[] args) throws Exception {
        FakeHost h = new FakeHost();
        HomeAssistant ha = new HomeAssistant(h);
        String b = ha.base();

        Map<String, JSONObject> d = ha.discovery(h.state, Collections.<String>emptyList(), Collections.<String>emptyList());
        JSONObject sel = d.get("homeassistant/select/" + ha.nodeId() + "/liveliness/config");
        check("activity select is discovered", sel != null);
        check("select topics", sel != null && (b + "/liveliness").equals(sel.optString("state_topic"))
                && (b + "/liveliness/set").equals(sel.optString("command_topic")));
        check("select options", sel != null && "[\"off\",\"quiet\",\"chatty\",\"full\"]".equals(sel.optJSONArray("options").toString()));

        check("state is published", "quiet".equals(ha.states(h.state).get(b + "/liveliness")));
        JSONObject unknown = new JSONObject(h.state.toString());
        unknown.remove("liveliness");
        check("no state until R2 Main reports one", !ha.states(unknown).containsKey(b + "/liveliness"));

        ha.onCommand(b + "/liveliness/set", "full");
        check("choosing a preset runs its action", h.actions.equals(Collections.singletonList("liveliness-full")));
        h.actions.clear();
        ha.onCommand(b + "/liveliness/set", "Chatty");
        check("case is ignored", h.actions.equals(Collections.singletonList("liveliness-chatty")));
        h.actions.clear();
        ha.onCommand(b + "/liveliness/set", "quiet");
        check("the current preset runs nothing", h.actions.isEmpty());
        ha.onCommand(b + "/liveliness/set", "patrol");
        ha.onCommand(b + "/liveliness/set", "off; reboot");
        check("unknown presets are ignored", h.actions.isEmpty());

        System.out.println("home assistant: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }
}
