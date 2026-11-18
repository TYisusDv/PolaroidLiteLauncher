package tv.polaroid.litehome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

/** Keeps the paired local remote alive and performs global actions across apps. */
public final class RemoteAccessibilityService extends AccessibilityService {
    private static volatile RemoteAccessibilityService activeInstance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LocalRemoteServer server;

    static boolean isActive(android.content.Context context) {
        // The preference can survive a process crash; only a live service owns the global server.
        return activeInstance != null;
    }

    static String getPin(android.content.Context context) {
        RemoteAccessibilityService service = activeInstance;
        if (service != null && service.server != null) return service.server.getPin();
        return null;
    }

    static boolean restartRemoteServer() {
        RemoteAccessibilityService service = activeInstance;
        return service != null && service.server != null && service.server.restart();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        activeInstance = this;
        server = new LocalRemoteServer(this, new LocalRemoteServer.ActionHandler() {
            @Override public void onAction(final String action) {
                mainHandler.post(new Runnable() {
                    @Override public void run() { performRemoteAction(action); }
                });
            }
        });
        server.start();
        HomeActivity.refreshRemoteServiceState();
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        if (activeInstance == this) activeInstance = null;
        HomeActivity.refreshRemoteServiceState();
        super.onDestroy();
    }

    private void performRemoteAction(String action) {
        if (RemoteMediaActions.dispatch(this, action)) {
            return;
        } else if (action.startsWith("text:")) {
            setFocusedText(action.substring(5));
        } else if ("power".equals(action)) {
            performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
        } else if ("voice".equals(action)) {
            try {
                Intent voice = new Intent("android.intent.action.VOICE_COMMAND");
                voice.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(voice);
            } catch (Exception ignored) { }
        } else if ("apps".equals(action)) {
            HomeActivity.openAppsPage(this);
        } else if ("home".equals(action)) {
            performGlobalAction(GLOBAL_ACTION_HOME);
        } else if ("back".equals(action)) {
            performGlobalAction(GLOBAL_ACTION_BACK);
        } else {
            AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
            boolean launcherIsActive = activeRoot != null
                    && getPackageName().contentEquals(activeRoot.getPackageName());
            if (activeRoot != null) activeRoot.recycle();
            if (launcherIsActive && HomeActivity.dispatchRemoteKey(action)) return;
            navigateAccessibilityTree(action);
        }
    }

    private void setFocusedText(String value) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null) focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (focused != null) {
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            focused.recycle();
        }
        root.recycle();
    }

    private void navigateAccessibilityTree(String action) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null) focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);

        if ("ok".equals(action)) {
            if (focused == null) focused = findFocusableNearCenter(root);
            clickFocused(focused);
            if (focused != null) focused.recycle();
            root.recycle();
            return;
        }
        if (focused == null) {
            AccessibilityNodeInfo first = findFocusableNearCenter(root);
            if (first != null) {
                first.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                first.recycle();
            }
            root.recycle();
            return;
        }

        Rect origin = new Rect();
        focused.getBoundsInScreen(origin);
        AccessibilityNodeInfo next = findNeighbor(root, focused, origin, action);
        if (next != null) {
            if (!next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            }
            next.recycle();
        }
        focused.recycle();
        root.recycle();
    }

    private void clickFocused(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; current != null && i < 5; i++) {
            if (current.isClickable()
                    && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                if (current != node) current.recycle();
                return;
            }
            AccessibilityNodeInfo parent = current.getParent();
            if (current != node) current.recycle();
            current = parent;
        }
        if (current != null && current != node) current.recycle();
        if (node != null) dispatchTap(node);
    }

    private void dispatchTap(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return;
        Path path = new Path();
        path.moveTo(bounds.centerX(), bounds.centerY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 90))
                .build();
        dispatchGesture(gesture, null, mainHandler);
    }

    private AccessibilityNodeInfo findFocusableNearCenter(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = collectNodes(root);
        float centerX = getResources().getDisplayMetrics().widthPixels / 2f;
        float centerY = getResources().getDisplayMetrics().heightPixels / 2f;
        AccessibilityNodeInfo best = null;
        double bestDistance = Double.MAX_VALUE;
        for (AccessibilityNodeInfo node : nodes) {
            if (!isNavigable(node)) continue;
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            double dx = bounds.centerX() - centerX;
            double dy = bounds.centerY() - centerY;
            double distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = node;
            }
        }
        for (AccessibilityNodeInfo node : nodes) {
            if (node != best) node.recycle();
        }
        return best;
    }

    private AccessibilityNodeInfo findNeighbor(AccessibilityNodeInfo root,
            AccessibilityNodeInfo focused, Rect origin, String direction) {
        List<AccessibilityNodeInfo> nodes = collectNodes(root);
        AccessibilityNodeInfo best = null;
        double bestScore = Double.MAX_VALUE;
        for (AccessibilityNodeInfo node : nodes) {
            if (node.equals(focused) || !isNavigable(node)) continue;
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            int dx = bounds.centerX() - origin.centerX();
            int dy = bounds.centerY() - origin.centerY();
            boolean horizontal = "left".equals(direction) || "right".equals(direction);
            int primary = horizontal ? dx : dy;
            int cross = horizontal ? dy : dx;
            if (("right".equals(direction) || "down".equals(direction)) && primary <= 0) continue;
            if (("left".equals(direction) || "up".equals(direction)) && primary >= 0) continue;
            double score = Math.abs(primary) + Math.abs(cross) * 1.7;
            if (score < bestScore) {
                bestScore = score;
                best = node;
            }
        }
        for (AccessibilityNodeInfo node : nodes) {
            if (node != best) node.recycle();
        }
        return best;
    }

    private List<AccessibilityNodeInfo> collectNodes(AccessibilityNodeInfo root) {
        ArrayList<AccessibilityNodeInfo> nodes = new ArrayList<>();
        ArrayList<AccessibilityNodeInfo> queue = new ArrayList<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        for (int index = 0; index < queue.size() && nodes.size() < 180; index++) {
            AccessibilityNodeInfo node = queue.get(index);
            nodes.add(node);
            if (node.getChildCount() > 0 && index < 180) {
                for (int child = 0; child < node.getChildCount(); child++) {
                    AccessibilityNodeInfo childNode = node.getChild(child);
                    if (childNode != null) queue.add(childNode);
                }
            }
        }
        for (int index = nodes.size(); index < queue.size(); index++) queue.get(index).recycle();
        return nodes;
    }

    private boolean isNavigable(AccessibilityNodeInfo node) {
        return node.isVisibleToUser() && (node.isFocusable() || node.isClickable());
    }
}
