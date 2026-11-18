package tv.polaroid.litehome;

import android.content.ComponentName;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import tv.polaroid.litehome.data.apps.PackageManagerAppsRepository;
import tv.polaroid.litehome.data.apps.InstalledApp;
import tv.polaroid.litehome.data.apps.AppsRepository;
import tv.polaroid.litehome.data.devices.AndroidTvInputRepository;
import tv.polaroid.litehome.data.devices.CvteInputSourceController;
import tv.polaroid.litehome.data.devices.TvDevice;
import tv.polaroid.litehome.data.devices.DevicesRepository;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/** Lightweight native home screen for Android 7 TVs and D-pad remotes. */
public final class HomeActivity extends FragmentActivity {
    private static volatile HomeActivity foregroundInstance;
    private static final int BG = Color.rgb(9, 12, 19);
    private static final int WHITE = Color.rgb(246, 248, 253);
    private static final int MUTED = Color.rgb(157, 165, 181);
    private static final int BLUE = Color.rgb(55, 143, 255);
    private static final String APP_USAGE_PREFS = "app_usage";
    private static final String RECENT_APPS_KEY = "recent_launch_order";

    private final ArrayList<InstalledApp> apps = new ArrayList<>();
    private final ArrayList<String> recentAppPackages = new ArrayList<>();
    private AppsRepository appsRepository;
    private DevicesRepository devicesRepository;
    private LinearLayout root;
    private FrameLayout content;
    private TextView clock;
    private ImageButton globalControlButton;
    private ImageButton phairPlayButton;
    private TextView phairPlayReceiverTab;
    private TextView phairPlaySettingsTab;
    private FrameLayout phairPlayFragmentSlot;
    private PopupWindow remotePopup;
    private ImageButton systemSettingsButton;
    private PopupWindow systemSettingsPopup;
    private TextView[] tabs;
    private String selectedTab = "Inicio";
    private String selectedPhairPlaySection = "Receptor";
    private LocalRemoteServer remoteServer;

    static void refreshRemoteServiceState() {
        final HomeActivity activity = foregroundInstance;
        if (activity == null) return;
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (RemoteAccessibilityService.isActive(activity)) activity.remoteServer.stop();
                else activity.remoteServer.start();
                activity.updateRemoteHint();
            }
        });
    }

    static boolean dispatchRemoteKey(String action) {
        HomeActivity activity = foregroundInstance;
        return activity != null && activity.dispatchNativeRemoteKey(action);
    }

    static void openAppsPage(Context context) {
        Intent intent = new Intent(context, HomeActivity.class);
        intent.putExtra("open_tab", "Apps");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
    }

    private boolean dispatchNativeRemoteKey(String action) {
        View current = getWindow().getDecorView().findFocus();
        if ("ok".equals(action)) {
            if (current != null && current.performClick()) return true;
            if (current == null && globalControlButton != null) globalControlButton.performClick();
            return true;
        }
        int direction = "right".equals(action) ? View.FOCUS_RIGHT
                : "left".equals(action) ? View.FOCUS_LEFT
                : "down".equals(action) ? View.FOCUS_DOWN
                : "up".equals(action) ? View.FOCUS_UP : View.NO_ID;
        if (direction == View.NO_ID) return false;
        if (current == null) current = tabs[0];
        View next = current == null ? null : current.focusSearch(direction);
        return next != null && next.requestFocus();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        appsRepository = new PackageManagerAppsRepository(this);
        devicesRepository = new AndroidTvInputRepository(this);
        loadRecentAppOrder();
        remoteServer = new LocalRemoteServer(this, new LocalRemoteServer.ActionHandler() {
            @Override public void onAction(final String action) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { dispatchLocalAction(action); }
                });
            }
        });
        buildShell();
        loadApps();
        showTab("Apps".equals(getIntent().getStringExtra("open_tab")) ? "Apps" : "Inicio", false);
        updateClock();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if ("Apps".equals(intent.getStringExtra("open_tab"))) showTab("Apps", true);
    }

    @Override protected void onResume() {
        super.onResume();
        foregroundInstance = this;
        if (RemoteAccessibilityService.isActive(this)) remoteServer.stop();
        else remoteServer.start();
        updateRemoteHint();
        loadApps();
        updateClock();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            if (RemoteAccessibilityService.isActive(this)) requestHomeFocus();
            else requestGlobalControlFocus();
        }
    }

    @Override protected void onPause() {
        if (foregroundInstance == this) foregroundInstance = null;
        dismissRemotePopup(false);
        remoteServer.stop();
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (remotePopup != null && remotePopup.isShowing()) {
            dismissRemotePopup(true);
            return;
        }
        if ("PhairPlay".equals(selectedTab)) {
            if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                super.onBackPressed();
                return;
            }
            if ("Ajustes".equals(selectedPhairPlaySection)) {
                showPhairPlaySection("Receptor", true);
            } else {
                showTab("Inicio", true);
            }
            return;
        }
        super.onBackPressed();
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(36), dp(18), dp(36), dp(12));
        root.setBackgroundColor(BG);
        root.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, dp(58)));
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = text("◉", 23, Color.rgb(160, 193, 255), Typeface.BOLD);
        logo.setGravity(Gravity.CENTER);
        bar.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(46)));
        TextView profile = text("POLAROID  ·  LITE HOME", 11, MUTED, Typeface.BOLD);
        profile.setLetterSpacing(.08f);
        LinearLayout.LayoutParams profileLp = new LinearLayout.LayoutParams(dp(225), -2);
        profileLp.leftMargin = dp(8);
        bar.addView(profile, profileLp);
        tabs = new TextView[3];
        String[] labels = {"Inicio", "Aplicaciones", "Fuentes"};
        for (int i = 0; i < labels.length; i++) {
            final String label = labels[i];
            TextView tab = text(label, 14, WHITE, Typeface.BOLD);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(18), 0, dp(18), 0);
            tab.setFocusable(true);
            tab.setClickable(true);
            tab.setBackground(roundSolid(Color.TRANSPARENT, dp(24)));
            addFocusMotion(tab, 1f, 0, dp(24));
            tab.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { showTab(label, true); }
            });
            tab.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View view, boolean focused) {
                    styleTab(tab, label.equals(selectedTab));
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
            lp.leftMargin = dp(5);
            bar.addView(tab, lp);
            tabs[i] = tab;
        }
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        globalControlButton = new ImageButton(this);
        globalControlButton.setImageResource(R.drawable.ic_remote_control);
        globalControlButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        globalControlButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        globalControlButton.setContentDescription("Abrir control remoto");
        globalControlButton.setFocusable(true);
        globalControlButton.setClickable(true);
        globalControlButton.setBackground(roundSolid(Color.rgb(29, 39, 57), dp(18)));
        addFocusMotion(globalControlButton, 1f, 0, dp(20));
        globalControlButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { toggleRemotePopup(); }
        });
        bar.addView(globalControlButton, new LinearLayout.LayoutParams(dp(42), dp(42)));
        phairPlayButton = new ImageButton(this);
        phairPlayButton.setImageResource(R.drawable.ic_cast);
        phairPlayButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        phairPlayButton.setPadding(dp(9), dp(9), dp(9), dp(9));
        phairPlayButton.setContentDescription("PhairPlay");
        phairPlayButton.setFocusable(true);
        phairPlayButton.setClickable(true);
        phairPlayButton.setBackground(roundSolid(Color.rgb(29, 39, 57), dp(18)));
        addFocusMotion(phairPlayButton, 1f, 0, dp(20));
        phairPlayButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showTab("PhairPlay", true); }
        });
        LinearLayout.LayoutParams phairPlayLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        phairPlayLp.leftMargin = dp(8);
        bar.addView(phairPlayButton, phairPlayLp);
        systemSettingsButton = new ImageButton(this);
        systemSettingsButton.setImageResource(R.drawable.ic_system_settings);
        systemSettingsButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        systemSettingsButton.setPadding(dp(9), dp(9), dp(9), dp(9));
        systemSettingsButton.setContentDescription("Ajustes del sistema y accesibilidad");
        systemSettingsButton.setFocusable(true);
        systemSettingsButton.setClickable(true);
        systemSettingsButton.setBackground(roundSolid(Color.rgb(29, 39, 57), dp(18)));
        addFocusMotion(systemSettingsButton, 1f, 0, dp(20));
        systemSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { toggleSystemSettingsPopup(); }
        });
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        settingsLp.leftMargin = dp(8);
        bar.addView(systemSettingsButton, settingsLp);
        clock = text("", 14, WHITE, Typeface.NORMAL);
        clock.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clockLp = new LinearLayout.LayoutParams(dp(82), dp(40));
        clockLp.leftMargin = dp(14);
        bar.addView(clock, clockLp);
        return bar;
    }

    private void showTab(String tab, boolean animate) {
        if ("Apps".equals(tab)) tab = "Aplicaciones";
        if (!"PhairPlay".equals(tab)) removePhairPlayFragment();
        selectedTab = tab;
        for (int i = 0; i < tabs.length; i++) {
            boolean selected = tabs[i].getText().toString().equals(tab);
            styleTab(tabs[i], selected);
        }
        View page = "Aplicaciones".equals(tab) ? buildAppsPage()
                : "Fuentes".equals(tab) ? buildSourcesPage()
                : "PhairPlay".equals(tab) ? buildPhairPlayPage() : buildHomePage();
        content.removeAllViews();
        content.addView(page, new FrameLayout.LayoutParams(-1, -1));
        phairPlayButton.setBackground(roundSolid("PhairPlay".equals(tab)
                ? Color.rgb(45, 71, 108) : Color.rgb(29, 39, 57), dp(18)));
        if (animate) {
            page.setAlpha(0f);
            page.setTranslationY(dp(8));
            page.animate().alpha(1f).translationY(0).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    /** PhairPlay receiver and settings stay inside Lite Home's content area. */
    private View buildPhairPlayPage() {
        com.phairplay.service.ServiceController.INSTANCE.start(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setPadding(0, dp(14), 0, dp(18));

        LinearLayout aside = column();
        aside.setPadding(0, dp(4), dp(18), 0);

        TextView phairPlayTitle = text("PhairPlay", 20, WHITE, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.bottomMargin = dp(22);
        aside.addView(phairPlayTitle, titleLp);

        phairPlayReceiverTab = phairPlaySectionTab("Receptor");
        phairPlaySettingsTab = phairPlaySectionTab("Ajustes");
        aside.addView(phairPlayReceiverTab, new LinearLayout.LayoutParams(-1, dp(50)));
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(-1, dp(50));
        settingsLp.topMargin = dp(8);
        aside.addView(phairPlaySettingsTab, settingsLp);
        LinearLayout.LayoutParams asideLp = new LinearLayout.LayoutParams(dp(218), -1);
        page.addView(aside, asideLp);

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(37, 47, 65));
        page.addView(divider, new LinearLayout.LayoutParams(dp(1), -1));

        phairPlayFragmentSlot = new FrameLayout(this);
        phairPlayFragmentSlot.setId(com.phairplay.R.id.phairplay_content_container);
        LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(0, -1, 1f);
        slotLp.leftMargin = dp(24);
        page.addView(phairPlayFragmentSlot, slotLp);
        phairPlayReceiverTab.setOnClickListener(v -> showPhairPlaySection("Receptor", true));
        phairPlaySettingsTab.setOnClickListener(v -> showPhairPlaySection("Ajustes", true));
        stylePhairPlaySectionTabs();
        phairPlayFragmentSlot.post(() -> {
            showPhairPlaySection(selectedPhairPlaySection, false);
            phairPlayReceiverTab.requestFocus();
        });
        return page;
    }

    private TextView phairPlaySectionTab(String label) {
        TextView tab = text(label, 14, WHITE, Typeface.BOLD);
        tab.setGravity(Gravity.CENTER_VERTICAL);
        tab.setPadding(dp(16), 0, dp(14), 0);
        tab.setFocusable(true);
        tab.setClickable(true);
        tab.setBackground(roundSolid(Color.rgb(20, 27, 40), dp(13)));
        addFocusMotion(tab, 1f, 0, dp(13));
        return tab;
    }

    private void stylePhairPlaySectionTabs() {
        stylePhairPlayAsideItem(phairPlayReceiverTab,
                "Receptor".equals(selectedPhairPlaySection));
        stylePhairPlayAsideItem(phairPlaySettingsTab,
                "Ajustes".equals(selectedPhairPlaySection));
    }

    private void stylePhairPlayAsideItem(TextView item, boolean selected) {
        if (item == null) return;
        item.setTextColor(selected ? Color.rgb(13, 17, 26) : WHITE);
        item.setBackground(roundSolid(selected ? Color.rgb(239, 243, 251)
                : Color.rgb(20, 27, 40), dp(13)));
    }

    private void showPhairPlaySection(String section, boolean animate) {
        if (!"PhairPlay".equals(selectedTab)) return;
        selectedPhairPlaySection = section;
        stylePhairPlaySectionTabs();
        Runnable swap = () -> {
            Fragment fragment = "Ajustes".equals(section)
                    ? new com.phairplay.ui.SettingsFragment() : new com.phairplay.ui.HomeFragment();
            getSupportFragmentManager().beginTransaction()
                    .replace(com.phairplay.R.id.phairplay_content_container, fragment)
                    .commitNowAllowingStateLoss();
            if (animate && phairPlayFragmentSlot != null) {
                phairPlayFragmentSlot.setAlpha(0f);
                phairPlayFragmentSlot.setTranslationX(-dp(12));
                phairPlayFragmentSlot.animate().alpha(1f).translationX(0f)
                        .setDuration(190).setInterpolator(new DecelerateInterpolator()).start();
            }
        };
        if (animate && phairPlayFragmentSlot != null) {
            phairPlayFragmentSlot.animate().alpha(0f).translationX(dp(12))
                    .setDuration(100).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(swap).start();
        } else {
            swap.run();
        }
    }

    private void removePhairPlayFragment() {
        getSupportFragmentManager().executePendingTransactions();
        Fragment fragment = getSupportFragmentManager()
                .findFragmentById(com.phairplay.R.id.phairplay_content_container);
        if (fragment != null) {
            getSupportFragmentManager().beginTransaction().remove(fragment).commitNowAllowingStateLoss();
        }
        phairPlayReceiverTab = null;
        phairPlaySettingsTab = null;
        phairPlayFragmentSlot = null;
    }

    private void styleTab(TextView view, boolean selected) {
        view.setTextColor(selected ? Color.rgb(13, 17, 26) : WHITE);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable background = roundSolid(selected ? Color.rgb(239, 243, 251)
                : Color.TRANSPARENT, dp(24));
        background.setStroke(dp(2), view.hasFocus() ? Color.rgb(112, 178, 255)
                : (selected ? Color.rgb(239, 243, 251) : Color.TRANSPARENT));
        view.setBackground(background);
    }

    private View buildHomePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout page = column();
        page.setPadding(0, dp(8), 0, dp(12));
        page.addView(buildHero(), new LinearLayout.LayoutParams(-1, dp(300)));
        page.addView(sectionTitle("Tus apps", "Tus aplicaciones listas para abrir"));
        page.addView(buildAppShelf(), new LinearLayout.LayoutParams(-1, dp(92)));
        page.addView(sectionTitle("Continúa viendo", "Tu actividad reciente aparecerá aquí"));
        page.addView(buildEmptyWatching(), new LinearLayout.LayoutParams(-1, dp(96)));
        page.addView(sectionTitle("Fuentes", "HDMI, VGA, YPbPr, AV y televisión"));
        page.addView(buildSourceShelf(), new LinearLayout.LayoutParams(-1, dp(92)));
        scroll.addView(page);
        return scroll;
    }

    private View buildHero() {
        FrameLayout hero = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(10, 15, 25), Color.rgb(38, 22, 39), Color.rgb(95, 29, 39)});
        bg.setCornerRadius(dp(25));
        hero.setBackground(bg);
        hero.setClipToOutline(true);
        hero.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        View glow = new View(this);
        GradientDrawable glowBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(15, 75, 149, 255), Color.argb(180, 183, 43, 51)});
        glowBg.setCornerRadius(dp(25));
        glow.setBackground(glowBg);
        hero.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout copy = column();
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setPadding(dp(34), dp(20), dp(20), dp(18));
        TextView eyebrow = text("POLAROID LITE HOME", 11, Color.rgb(210, 218, 235), Typeface.BOLD);
        eyebrow.setLetterSpacing(.1f);
        copy.addView(eyebrow);
        TextView title = text("Todo lo que te gusta,\na un control de distancia.", 30, WHITE, Typeface.BOLD);
        title.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(13);
        copy.addView(title, titleLp);
        TextView description = text("Abre tus apps, retoma tu entretenimiento y cambia de entrada.",
                14, Color.rgb(216, 219, 230), Typeface.NORMAL);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
        descLp.topMargin = dp(8);
        copy.addView(description, descLp);
        TextView openApps = pill("Ver apps", true);
        openApps.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showTab("Apps", true); }
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-2, dp(42));
        btnLp.topMargin = dp(18);
        copy.addView(openApps, btnLp);
        FrameLayout.LayoutParams copyLp = new FrameLayout.LayoutParams(dp(620), -1, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        hero.addView(copy, copyLp);
        TextView visual = text("✦", 118, Color.argb(100, 255, 255, 255), Typeface.NORMAL);
        visual.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams visualLp = new FrameLayout.LayoutParams(dp(350), -1, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        hero.addView(visual, visualLp);
        return hero;
    }

    private View sectionTitle(String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = column();
        TextView heading = text(title, 18, WHITE, Typeface.BOLD);
        TextView sub = text(subtitle, 10, MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
        subLp.topMargin = dp(3);
        titles.addView(heading);
        titles.addView(sub, subLp);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLp.topMargin = dp(12);
        titleLp.bottomMargin = dp(10);
        row.addView(titles, titleLp);
        return row;
    }

    private View buildAppShelf() {
        ArrayList<InstalledApp> homeApps = new ArrayList<>();
        for (InstalledApp app : apps) if (!isCuratedSystemApp(app)) homeApps.add(app);
        Collections.sort(homeApps, new Comparator<InstalledApp>() {
            @Override public int compare(InstalledApp a, InstalledApp b) {
                int aRank = recentAppPackages.indexOf(a.packageName);
                int bRank = recentAppPackages.indexOf(b.packageName);
                if (aRank < 0) aRank = Integer.MAX_VALUE;
                if (bRank < 0) bRank = Integer.MAX_VALUE;
                return aRank != bRank ? aRank - bRank : a.label.compareToIgnoreCase(b.label);
            }
        });
        return buildAppShelf(homeApps, dp(160), dp(88));
    }

    private View buildAppShelf(List<InstalledApp> shelfApps, int cardWidth, int cardHeight) {
        android.widget.HorizontalScrollView horizontal = new android.widget.HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        horizontal.setClipChildren(false);
        horizontal.setClipToPadding(false);
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        for (InstalledApp app : shelfApps) {
            row.addView(appImageCard(app, cardWidth, cardHeight));
        }
        if (shelfApps.isEmpty()) row.addView(infoCard("Sin aplicaciones", "No hay apps en esta sección", dp(300), cardHeight));
        horizontal.addView(row);
        return horizontal;
    }

    private View buildEmptyWatching() {
        LinearLayout empty = new LinearLayout(this);
        empty.setGravity(Gravity.CENTER_VERTICAL);
        empty.setPadding(dp(18), 0, dp(18), 0);
        empty.setBackground(roundSolid(Color.rgb(19, 24, 34), dp(15)));
        TextView icon = text("▶", 22, Color.rgb(115, 166, 255), Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        empty.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout labels = column();
        labels.setPadding(dp(13), 0, 0, 0);
        labels.addView(text("Tu lista está lista para empezar", 13, WHITE, Typeface.BOLD));
        TextView sub = text("Cuando haya contenido reciente, lo verás aquí.", 10, MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(4);
        labels.addView(sub, lp);
        empty.addView(labels);
        return empty;
    }

    private View buildSourceShelf() {
        android.widget.HorizontalScrollView horizontal = new android.widget.HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        horizontal.setClipChildren(false);
        horizontal.setClipToPadding(false);
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        List<TvDevice> sources = sourcesForUi();
        for (TvDevice source : sources) {
            row.addView(deviceShelfCard(source, dp(160), dp(88)));
        }
        horizontal.addView(row);
        return horizontal;
    }

    private View buildAppsPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout page = column();
        page.setPadding(0, dp(14), 0, dp(20));
        page.addView(text("Tus aplicaciones", 25, WHITE, Typeface.BOLD));
        page.addView(text(apps.size() + (apps.size() == 1 ? " aplicación" : " aplicaciones") + " instaladas",
                12, MUTED, Typeface.NORMAL), topMargin(dp(6)));
        ArrayList<InstalledApp> personalApps = new ArrayList<>();
        ArrayList<InstalledApp> systemApps = new ArrayList<>();
        for (InstalledApp app : apps) {
            (isCuratedSystemApp(app) ? systemApps : personalApps).add(app);
        }
        Collections.sort(personalApps, new Comparator<InstalledApp>() {
            @Override public int compare(InstalledApp a, InstalledApp b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        Collections.sort(systemApps, new Comparator<InstalledApp>() {
            @Override public int compare(InstalledApp a, InstalledApp b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        if (!personalApps.isEmpty()) {
            page.addView(sectionTitle("Mis apps", "Aplicaciones instaladas por ti"));
            page.addView(buildAppShelf(personalApps, dp(205), dp(126)),
                    new LinearLayout.LayoutParams(-1, dp(126)));
        }
        if (!systemApps.isEmpty()) {
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
            titleLp.topMargin = dp(8);
            page.addView(sectionTitle("Sistema", "Herramientas incluidas con el televisor"), titleLp);
            page.addView(buildAppShelf(systemApps, dp(205), dp(126)),
                    new LinearLayout.LayoutParams(-1, dp(126)));
        }
        if (personalApps.isEmpty() && systemApps.isEmpty()) {
            page.addView(infoCard("No hay aplicaciones", "Las apps disponibles aparecerán aquí",
                    dp(320), dp(90)), topMargin(dp(22)));
        }
        scroll.addView(page);
        return scroll;
    }

    private View buildSourcesPage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = column();
        page.setPadding(0, dp(14), 0, dp(20));
        page.addView(text("Fuentes", 25, WHITE, Typeface.BOLD));
        page.addView(text("Elige la entrada de imagen de tu televisor.", 12, MUTED, Typeface.NORMAL), topMargin(dp(6)));
        android.widget.HorizontalScrollView horizontal = new android.widget.HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        horizontal.setClipChildren(false);
        horizontal.setClipToPadding(false);
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setClipChildren(false);
        for (TvDevice source : sourcesForUi()) {
            cards.addView(deviceShelfCard(source, dp(200), dp(112)));
        }
        LinearLayout.LayoutParams cardsLp = new LinearLayout.LayoutParams(-1, dp(112));
        cardsLp.topMargin = dp(18);
        horizontal.addView(cards);
        page.addView(horizontal, cardsLp);
        scroll.addView(page);
        return scroll;
    }

    private List<TvDevice> sourcesForUi() {
        ArrayList<TvDevice> discovered = new ArrayList<>(passthroughInputs());
        ArrayList<TvDevice> sources = new ArrayList<>();
        for (int port = 1; port <= 3; port++) {
            int hardwareId = port + 3;
            TvDevice discoveredInput = null;
            for (TvDevice input : discovered) {
                if (inputHardwareId(input.id) == hardwareId) {
                    discoveredInput = input;
                    break;
                }
            }
            String id = discoveredInput == null ? "source/HW" + hardwareId : discoveredInput.id;
            sources.add(new TvDevice(id, "HDMI " + port, "HDMI", true));
        }
        // Hardware IDs 2 and 3 are component and VGA, respectively, on this CVTE board.
        sources.add(new TvDevice("source/HW3", "VGA", "PC", true));
        sources.add(new TvDevice("source/HW1", "TV", "Antena", true));
        // CVTE/MTK firmware uses hardware source IDs 0 and 1 for AV and TV.
        sources.add(new TvDevice("source/HW0", "AV", "AV", true));
        sources.add(new TvDevice("source/HW2", "YPbPr", "Componente", true));
        return sources;
    }

    private List<TvDevice> passthroughInputs() {
        ArrayList<TvDevice> result = new ArrayList<>();
        for (TvDevice device : devicesRepository.getInputs()) if (device.passthrough) result.add(device);
        Collections.sort(result, new Comparator<TvDevice>() {
            @Override public int compare(TvDevice a, TvDevice b) {
                int type = a.typeLabel.compareToIgnoreCase(b.typeLabel);
                if (type != 0) return type;
                return inputHardwareId(a.id) - inputHardwareId(b.id);
            }
        });
        return result;
    }

    private int sourceIconResource(String sourceLabel) {
        String label = normalizedAppLabel(sourceLabel);
        if (label.startsWith("hdmi")) return R.drawable.ic_source_hdmi;
        if ("vga".equals(label)) return R.drawable.ic_source_vga;
        if ("ypbpr".equals(label) || "componente".equals(label)) return R.drawable.ic_source_component;
        if ("av".equals(label)) return R.drawable.ic_source_av;
        return R.drawable.ic_source_tv;
    }

    private View deviceShelfCard(final TvDevice device, int width, int height) {
        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setClickable(true);
        card.setContentDescription(device.label + " · cambiar entrada");
        card.setBackground(roundStroke(Color.rgb(20, 31, 48), Color.rgb(42, 53, 73), dp(13)));
        addFocusMotion(card, 1f, 0, dp(13));
        ImageView icon = new ImageView(this);
        icon.setImageResource(sourceIconResource(device.label));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(42), dp(42),
                Gravity.LEFT | Gravity.CENTER_VERTICAL);
        iconLp.leftMargin = dp(10);
        card.addView(icon, iconLp);
        TextView label = text(device.label, 12, WHITE, Typeface.BOLD);
        label.setMaxLines(2);
        label.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(-1, -1,
                Gravity.CENTER_VERTICAL);
        labelLp.leftMargin = dp(59);
        labelLp.rightMargin = dp(7);
        card.addView(label, labelLp);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { openDevice(device); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
        lp.rightMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    private View deviceCard(final TvDevice device, int width, int height) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(17), dp(10), dp(17), dp(10));
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(roundStroke(Color.rgb(21, 27, 38), Color.rgb(42, 53, 73), dp(15)));
        addFocusMotion(card, 1f, 0, dp(15));
        ImageView icon = new ImageView(this);
        icon.setImageResource(sourceIconResource(device.label));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(42)));
        LinearLayout labels = column();
        labels.setPadding(dp(9), 0, 0, 0);
        labels.addView(text(device.label, 13, WHITE, Typeface.BOLD));
        labels.addView(text(device.typeLabel, 10, MUTED, Typeface.NORMAL), topMargin(dp(3)));
        card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(text("›", 25, MUTED, Typeface.NORMAL));
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { openDevice(device); }
        });
        card.setLayoutParams(new LinearLayout.LayoutParams(width, height));
        return card;
    }

    private void openDevice(TvDevice device) {
        final String sourceLabel = device.label;
        CvteInputSourceController.select(this, sourceLabel, inputHardwareId(device.id),
                new CvteInputSourceController.Callback() {
                    @Override public void onResult(boolean selected) {
                        if (!selected) {
                            Toast.makeText(HomeActivity.this, "No se pudo cambiar a " + sourceLabel,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Intent tvHome = new Intent(Intent.ACTION_MAIN);
                        tvHome.addCategory("android.intent.category.TV_HOME");
                        tvHome.putExtra("isLauncherGoToTv", true);
                        tvHome.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try { startActivity(tvHome); }
                        catch (Exception error) {
                            Toast.makeText(HomeActivity.this, "Entrada cambiada a " + sourceLabel,
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    private int inputHardwareId(String id) {
        int marker = id.lastIndexOf("HW");
        if (marker >= 0) {
            try { return Integer.parseInt(id.substring(marker + 2)); }
            catch (NumberFormatException ignored) { }
        }
        return -1;
    }

    private View appImageCard(final InstalledApp app, int width, int height) {
        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setClickable(true);
        card.setContentDescription(app.label);
        boolean netflix = "netflix".equals(normalizedAppLabel(app.label));
        boolean useBanner = !netflix && app.banner != null;
        card.setBackground(roundSolid(Color.rgb(42, 47, 56), dp(10)));
        card.setClipToOutline(true);
        addFocusMotion(card, 1f, 0, dp(10));
        if (netflix || useBanner) {
            ImageView artwork = new ImageView(this);
            artwork.setScaleType(ImageView.ScaleType.FIT_XY);
            artwork.setImageDrawable(netflix ? getDrawable(R.drawable.brand_netflix) : app.banner);
            FrameLayout.LayoutParams artworkLp = new FrameLayout.LayoutParams(-1, -1);
            card.addView(artwork, artworkLp);
        } else {
            LinearLayout brand = new LinearLayout(this);
            brand.setGravity(Gravity.CENTER);
            brand.setOrientation(LinearLayout.HORIZONTAL);
            brand.setPadding(dp(10), dp(7), dp(10), dp(7));
            ImageView icon = new ImageView(this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImageDrawable(app.icon);
            brand.addView(icon, new LinearLayout.LayoutParams(dp(37), dp(37)));
            TextView name = text(brandDisplayName(app.label), 12, WHITE, Typeface.BOLD);
            name.setGravity(Gravity.CENTER_VERTICAL);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, -1, 1f);
            nameLp.leftMargin = dp(7);
            brand.addView(name, nameLp);
            card.addView(brand, new FrameLayout.LayoutParams(-1, -1));
        }
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { launchApp(app); }
        });
        card.setLayoutParams(new LinearLayout.LayoutParams(width, height));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
        lp.rightMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    private boolean isCuratedSystemApp(InstalledApp app) {
        String label = normalizedAppLabel(app.label);
        return "com.android.providers.downloads.ui".equals(app.packageName)
                || "com.cvte.tv.media".equals(app.packageName)
                || "com.cvte.tv.androidsetting".equals(app.packageName)
                || "descargas".equals(label) || "downloads".equals(label)
                || "ajustes".equals(label)
                || "multimedia".equals(label) || "tv master".equals(label);
    }

    private String normalizedAppLabel(String label) {
        return java.text.Normalizer.normalize(label, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String brandDisplayName(String appLabel) {
        String label = normalizedAppLabel(appLabel);
        if ("lista de aplicaciones".equals(label)) return "Apps";
        if ("pantalla inalambrica".equals(label)) return "Pantalla";
        return appLabel;
    }

    private View infoCard(String title, String subtitle, int width, int height) {
        LinearLayout card = column();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(8), dp(16), dp(8));
        card.setBackground(roundStroke(Color.rgb(19, 25, 37), Color.rgb(34, 43, 60), dp(14)));
        card.addView(text(title, 12, WHITE, Typeface.BOLD));
        card.addView(text(subtitle, 10, MUTED, Typeface.NORMAL), topMargin(dp(4)));
        card.setLayoutParams(new LinearLayout.LayoutParams(width, height));
        return card;
    }

    private TextView pill(String label, boolean light) {
        TextView button = text(label, 13, light ? Color.rgb(14, 18, 26) : WHITE, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(19), 0, dp(19), 0);
        button.setFocusable(true);
        button.setClickable(true);
        button.setBackground(roundSolid(light ? Color.rgb(239, 243, 251) : BLUE, dp(24)));
        addFocusMotion(button, 1f, 0, dp(24));
        return button;
    }

    private void loadApps() {
        List<InstalledApp> loaded = appsRepository.getLaunchableApps();
        apps.clear();
        apps.addAll(loaded);
        showTab(selectedTab, false);
    }

    private void loadRecentAppOrder() {
        String stored = getSharedPreferences(APP_USAGE_PREFS, MODE_PRIVATE)
                .getString(RECENT_APPS_KEY, "");
        recentAppPackages.clear();
        if (stored.length() == 0) return;
        for (String packageName : stored.split("\\|")) {
            if (packageName.length() > 0 && !recentAppPackages.contains(packageName)) {
                recentAppPackages.add(packageName);
            }
        }
    }

    private void recordAppLaunch(InstalledApp app) {
        recentAppPackages.remove(app.packageName);
        recentAppPackages.add(0, app.packageName);
        while (recentAppPackages.size() > 30) recentAppPackages.remove(recentAppPackages.size() - 1);
        StringBuilder value = new StringBuilder();
        for (String packageName : recentAppPackages) {
            if (value.length() > 0) value.append('|');
            value.append(packageName);
        }
        getSharedPreferences(APP_USAGE_PREFS, MODE_PRIVATE).edit()
                .putString(RECENT_APPS_KEY, value.toString()).apply();
    }

    private void launchApp(InstalledApp app) {
        try {
            startActivity(app.launchIntent);
            recordAppLaunch(app);
        }
        catch (Exception ignored) { loadApps(); }
    }

    private void updateClock() {
        if (clock != null) clock.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));
    }

    private void updateRemoteHint() {
        if (globalControlButton == null) return;
        globalControlButton.setContentDescription(RemoteAccessibilityService.isActive(this)
                ? "Control remoto; control global activo"
                : "Control remoto; control global inactivo");
        if (remotePopup != null && remotePopup.isShowing()) {
            dismissRemotePopup(false);
            showRemotePopup();
        }
    }

    private void toggleRemotePopup() {
        if (remotePopup != null && remotePopup.isShowing()) dismissRemotePopup(true);
        else showRemotePopup();
    }

    private void toggleSystemSettingsPopup() {
        if (systemSettingsPopup != null && systemSettingsPopup.isShowing()) {
            systemSettingsPopup.dismiss();
            systemSettingsPopup = null;
        } else {
            showSystemSettingsPopup();
        }
    }

    private void showSystemSettingsPopup() {
        if (systemSettingsButton == null || isFinishing()) return;
        LinearLayout panel = column();
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        panel.setBackground(roundStroke(Color.rgb(20, 25, 36), Color.rgb(58, 68, 87), dp(18)));
        panel.setElevation(dp(16));
        TextView heading = text("Ajustes del sistema", 17, WHITE, Typeface.BOLD);
        panel.addView(heading, bottomMargin(dp(10)));

        View tvSettings = settingsAction("Ajustes del televisor", "Red, imagen y opciones del equipo",
                R.drawable.ic_system_settings);
        tvSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                dismissSystemSettingsPopup();
                openTelevisionSettings();
            }
        });
        panel.addView(tvSettings, bottomMargin(dp(8)));

        View accessibility = settingsAction("Accesibilidad", "Abrir los ajustes del televisor",
                R.drawable.ic_accessibility);
        accessibility.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                dismissSystemSettingsPopup();
                openTelevisionSettings();
            }
        });
        panel.addView(accessibility);

        systemSettingsPopup = new PopupWindow(panel, Math.min(dp(350),
                getResources().getDisplayMetrics().widthPixels - dp(28)), -2, true);
        systemSettingsPopup.setBackgroundDrawable(roundSolid(Color.TRANSPARENT, dp(18)));
        systemSettingsPopup.setOutsideTouchable(true);
        systemSettingsPopup.setElevation(dp(18));
        systemSettingsPopup.setOnDismissListener(new PopupWindow.OnDismissListener() {
            @Override public void onDismiss() { systemSettingsPopup = null; }
        });
        systemSettingsPopup.showAsDropDown(systemSettingsButton, -dp(8), dp(8), Gravity.RIGHT);
        panel.setAlpha(0f);
        panel.setScaleX(.94f);
        panel.setScaleY(.94f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170)
                .setInterpolator(new DecelerateInterpolator()).start();
        tvSettings.requestFocus();
    }

    private View settingsAction(String title, String description, int iconResource) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(roundStroke(Color.rgb(38, 44, 56), Color.rgb(50, 58, 72), dp(13)));
        addFocusMotion(row, 1f, 0, dp(13));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        icon.setPadding(dp(4), dp(4), dp(4), dp(4));
        row.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout labels = column();
        LinearLayout.LayoutParams labelsLp = new LinearLayout.LayoutParams(0, -2, 1f);
        labelsLp.leftMargin = dp(10);
        labels.addView(text(title, 14, WHITE, Typeface.BOLD));
        labels.addView(text(description, 10, MUTED, Typeface.NORMAL), topMargin(dp(2)));
        row.addView(labels, labelsLp);
        TextView arrow = text("›", 22, MUTED, Typeface.NORMAL);
        row.addView(arrow);
        return row;
    }

    private void openTelevisionSettings() {
        Intent intent = new Intent("android.settings.SETTINGS");
        intent.setComponent(new ComponentName("com.cvte.tv.androidsetting",
                "com.cvte.tv.androidsetting.GlobalSettingActivity"));
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudieron abrir los ajustes del televisor", Toast.LENGTH_LONG).show();
        }
    }

    private void dismissSystemSettingsPopup() {
        if (systemSettingsPopup != null) {
            systemSettingsPopup.dismiss();
            systemSettingsPopup = null;
        }
    }

    private void showRemotePopup() {
        if (globalControlButton == null || isFinishing()) return;
        LinearLayout panel = column();
        panel.setPadding(dp(18), dp(16), dp(18), dp(16));
        panel.setBackground(roundStroke(Color.rgb(20, 25, 36), Color.rgb(58, 68, 87), dp(20)));
        panel.setElevation(dp(16));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_remote_control);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        icon.setBackground(roundSolid(Color.rgb(42, 49, 66), dp(30)));
        heading.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout headingCopy = column();
        headingCopy.setPadding(dp(10), 0, 0, 0);
        headingCopy.addView(text("Control remoto", 18, WHITE, Typeface.BOLD));
        headingCopy.addView(text("Empareja o controla esta TV desde la web", 10, MUTED, Typeface.NORMAL), topMargin(dp(3)));
        heading.addView(headingCopy, new LinearLayout.LayoutParams(0, -2, 1f));
        panel.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        String ip = LocalRemoteServer.getLocalIpv4();
        String url = ip == null ? "Sin conexión Wi-Fi" : "http://" + ip + ":" + LocalRemoteServer.PORT + "/";
        String pin = RemoteAccessibilityService.getPin(this);
        if (pin == null) pin = remoteServer.getPin();
        LinearLayout details = column();
        LinearLayout.LayoutParams detailsLp = new LinearLayout.LayoutParams(-1, -2);
        detailsLp.topMargin = dp(14);
        details.addView(remoteInfoRow(R.drawable.ic_remote_network, "URL", url));
        details.addView(remoteInfoRow(R.drawable.ic_remote_ip, "IP local", ip == null ? "No disponible" : ip));
        details.addView(remoteInfoRow(R.drawable.ic_remote_lock, "PIN", pin));
        panel.addView(details, detailsLp);

        LinearLayout control = new LinearLayout(this);
        control.setGravity(Gravity.CENTER_VERTICAL);
        control.setPadding(dp(12), dp(8), dp(10), dp(8));
        control.setBackground(roundSolid(Color.rgb(38, 44, 56), dp(14)));
        LinearLayout controlLabels = column();
        controlLabels.addView(text("Control global", 13, WHITE, Typeface.BOLD));
        TextView status = text(RemoteAccessibilityService.isActive(this) ? "● Activo" : "● Inactivo",
                11, RemoteAccessibilityService.isActive(this) ? Color.rgb(43, 208, 117) : MUTED, Typeface.NORMAL);
        controlLabels.addView(status, topMargin(dp(3)));
        control.addView(controlLabels, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch globalSwitch = new Switch(this);
        boolean globalControlActive = RemoteAccessibilityService.isActive(this);
        globalSwitch.setChecked(globalControlActive);
        globalSwitch.setContentDescription(globalControlActive
                ? "Control global activo" : "Control global inactivo");
        globalSwitch.setThumbTintList(ColorStateList.valueOf(WHITE));
        globalSwitch.setTrackTintList(ColorStateList.valueOf(globalControlActive
                ? BLUE : Color.rgb(73, 81, 96)));
        globalSwitch.setEnabled(false);
        globalSwitch.setClickable(false);
        globalSwitch.setFocusable(false);
        globalSwitch.setAlpha(1f);
        control.addView(globalSwitch);
        LinearLayout.LayoutParams controlLp = new LinearLayout.LayoutParams(-1, dp(54));
        panel.addView(control, controlLp);

        LinearLayout restart = new LinearLayout(this);
        restart.setOrientation(LinearLayout.HORIZONTAL);
        restart.setGravity(Gravity.CENTER);
        restart.setFocusable(true);
        restart.setClickable(true);
        restart.setBackground(roundStroke(Color.rgb(42, 48, 61), Color.rgb(62, 72, 91), dp(14)));
        addFocusMotion(restart, 1f, 0, dp(14));
        ImageView restartIcon = new ImageView(this);
        restartIcon.setImageResource(R.drawable.ic_remote_refresh);
        restart.addView(restartIcon, new LinearLayout.LayoutParams(dp(21), dp(21)));
        TextView restartLabel = text("Reiniciar servidor", 14, WHITE, Typeface.BOLD);
        LinearLayout.LayoutParams restartTextLp = new LinearLayout.LayoutParams(-2, -2);
        restartTextLp.leftMargin = dp(8);
        restart.addView(restartLabel, restartTextLp);
        restart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                restart.setEnabled(false);
                new Thread(new Runnable() {
                    @Override public void run() {
                        boolean started = false;
                        if (RemoteAccessibilityService.isActive(HomeActivity.this)) {
                            started = RemoteAccessibilityService.restartRemoteServer();
                        }
                        if (!started) started = remoteServer.restart();
                        final boolean succeeded = started;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (restart.isAttachedToWindow()) restart.setEnabled(true);
                                Toast.makeText(HomeActivity.this, succeeded
                                        ? "Servidor reiniciado" : "No se pudo iniciar el servidor",
                                        Toast.LENGTH_SHORT).show();
                                updateRemoteHint();
                            }
                        });
                    }
                }, "polaroid-server-restart").start();
            }
        });
        LinearLayout.LayoutParams restartLp = new LinearLayout.LayoutParams(-1, dp(42));
        restartLp.topMargin = dp(7);
        panel.addView(restart, restartLp);

        remotePopup = new PopupWindow(panel, Math.min(dp(380), getResources().getDisplayMetrics().widthPixels - dp(28)),
                -2, true);
        remotePopup.setBackgroundDrawable(roundSolid(Color.TRANSPARENT, dp(20)));
        remotePopup.setOutsideTouchable(true);
        remotePopup.setElevation(dp(18));
        remotePopup.setOnDismissListener(new PopupWindow.OnDismissListener() {
            @Override public void onDismiss() { remotePopup = null; }
        });
        remotePopup.showAsDropDown(globalControlButton, -dp(8), dp(8), Gravity.RIGHT);
        panel.setAlpha(0f);
        panel.setScaleX(.94f);
        panel.setScaleY(.94f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170)
                .setInterpolator(new DecelerateInterpolator()).start();
        restart.requestFocus();
    }

    private View remoteInfoRow(int iconResource, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(8), 0);
        row.setBackground(roundStroke(Color.rgb(38, 44, 56), Color.rgb(50, 58, 72), dp(12)));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        icon.setPadding(dp(5), dp(5), dp(5), dp(5));
        row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(42)));
        TextView title = text(label, 12, Color.rgb(199, 207, 222), Typeface.NORMAL);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(dp(60), -1);
        titleLp.leftMargin = dp(4);
        row.addView(title, titleLp);
        TextView data = text(value, 13, WHITE, Typeface.BOLD);
        data.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        data.setSingleLine(true);
        data.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(data, new LinearLayout.LayoutParams(0, -1, 1f));
        ImageButton copy = new ImageButton(this);
        copy.setImageResource(R.drawable.ic_remote_copy);
        copy.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        copy.setPadding(dp(8), dp(8), dp(8), dp(8));
        copy.setBackground(roundSolid(Color.TRANSPARENT, dp(8)));
        copy.setFocusable(true);
        copy.setClickable(true);
        copy.setContentDescription("Copiar " + label);
        addFocusMotion(copy, 1f, 0, dp(8));
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
                Toast.makeText(HomeActivity.this, label + " copiado", Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(copy, new LinearLayout.LayoutParams(dp(38), dp(42)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(44));
        lp.bottomMargin = dp(7);
        row.setLayoutParams(lp);
        return row;
    }

    private void dismissRemotePopup(boolean animate) {
        final PopupWindow popup = remotePopup;
        if (popup == null || !popup.isShowing()) return;
        if (!animate || popup.getContentView() == null) {
            popup.dismiss();
            return;
        }
        popup.getContentView().animate().alpha(0f).scaleX(.95f).scaleY(.95f).setDuration(130)
                .withEndAction(new Runnable() {
                    @Override public void run() { if (popup.isShowing()) popup.dismiss(); }
                }).start();
    }

    private void requestGlobalControlFocus() {
        if (globalControlButton == null) return;
        globalControlButton.post(new Runnable() {
            @Override public void run() {
                if (globalControlButton != null && globalControlButton.isShown()) globalControlButton.requestFocus();
            }
        });
    }

    private void requestHomeFocus() {
        if (tabs == null || tabs.length == 0) return;
        tabs[0].post(new Runnable() {
            @Override public void run() {
                if (tabs != null && tabs[0].isShown()) tabs[0].requestFocus();
            }
        });
    }

    private void dispatchLocalAction(String action) {
        if (RemoteMediaActions.dispatch(this, action)) return;
        if ("apps".equals(action)) {
            showTab("Apps", true);
            return;
        }
        if ("voice".equals(action)) {
            try {
                Intent voice = new Intent("android.intent.action.VOICE_COMMAND");
                voice.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(voice);
            } catch (Exception ignored) { }
            return;
        }
        if ("power".equals(action)) {
            try {
                Intent shutdown = new Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN");
                shutdown.putExtra("android.intent.extra.KEY_CONFIRM", true);
                shutdown.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(shutdown);
            } catch (Exception ignored) { }
            return;
        }
        if ("home".equals(action)) {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(home); } catch (Exception ignored) { }
            return;
        }
        int keyCode = "up".equals(action) ? android.view.KeyEvent.KEYCODE_DPAD_UP
                : "down".equals(action) ? android.view.KeyEvent.KEYCODE_DPAD_DOWN
                : "left".equals(action) ? android.view.KeyEvent.KEYCODE_DPAD_LEFT
                : "right".equals(action) ? android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                : "ok".equals(action) ? android.view.KeyEvent.KEYCODE_DPAD_CENTER
                : "back".equals(action) ? android.view.KeyEvent.KEYCODE_BACK : 0;
        if (keyCode == 0) return;
        long now = android.os.SystemClock.uptimeMillis();
        dispatchKeyEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0));
        dispatchKeyEvent(new android.view.KeyEvent(now, now + 35, android.view.KeyEvent.ACTION_UP, keyCode, 0));
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout.LayoutParams topMargin(int size) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = size;
        return lp;
    }

    private LinearLayout.LayoutParams bottomMargin(int size) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = size;
        return lp;
    }

    private TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable roundSolid(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private GradientDrawable roundStroke(int color, int stroke, int radius) {
        GradientDrawable drawable = roundSolid(color, radius);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private void addFocusMotion(final View view, final float scale, final int lift) {
        addFocusMotion(view, scale, lift, dp(12));
    }

    private void addFocusMotion(final View view, final float scale, final int lift,
                                final int focusRadius) {
        view.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View focusedView, boolean focused) {
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    focusedView.setForeground(focused
                            ? roundStroke(Color.TRANSPARENT, Color.rgb(112, 178, 255), focusRadius)
                            : null);
                }
                focusedView.animate().scaleX(1f).scaleY(1f).translationY(0f)
                        .setDuration(120).setInterpolator(new DecelerateInterpolator()).start();
            }
        });
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

}
