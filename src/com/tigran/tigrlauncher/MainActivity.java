package com.tigran.tigrlauncher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final boolean DEBUG = false;

    private static final String PREFS = "tigrlauncher";
    private static final String KEY_FAV = "fav";
    private static final String KEY_RECENT = "recent";
    private static final int RECENT_MAX = 8;
    private static final String LIVE_TV_PKG = "com.android.tv";
    private static final String LIVE_TV_CLS = "com.android.tv.TvActivity";
    private static final String FOCUS_SHELF = "shelf";

    private static final float FOCUS_SCALE = 1.09f;
    private static final long FOCUS_ANIM_MS = 140L;
    private static final long WASH_MS = 340L;
    private static final float CARD_RADIUS_DP = 12f;
    private static final float SHADOW_DP = 12f;

    private static final int ART_W = 320;
    private static final int ART_H = 160;
    private static final float ICON_BLEED = 0.86f;
    private static final int ART_FALLBACK_TINT = 0xFF2A2F3A;

    private static final int WASH_TOP_DEFAULT = 0xFF1A1520;
    private static final int WASH_BOTTOM_DEFAULT = 0xFF000000;

    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private PackageManager pm;
    private LayoutInflater inflater;
    private View root;
    private GridView grid;
    private LinearLayout shelfRow;
    private View shelfScroll;
    private TextView favEmpty;
    private TextView clock;
    private TextView counter;
    private EditText filter;
    private GridAdapter adapter;

    private GradientDrawable wash;
    private ValueAnimator washAnim;
    private int washTop = WASH_TOP_DEFAULT;
    private int washBottom = WASH_BOTTOM_DEFAULT;

    private final Handler ui = new Handler();
    private final Executor iconLoader = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> iconCache = new HashMap<String, Bitmap>();
    private final Map<String, Integer> tintCache = new HashMap<String, Integer>();
    private final Set<String> iconInFlight = Collections.synchronizedSet(new HashSet<String>());
    private final List<Entry> all = new ArrayList<Entry>();
    private final List<Entry> shown = new ArrayList<Entry>();
    private final List<String> fav = new ArrayList<String>();
    private final List<String> recent = new ArrayList<String>();
    private final Map<String, ArrayList<WeakReference<ImageView>>> pendingViews =
            new HashMap<String, ArrayList<WeakReference<ImageView>>>();
    private final Map<String, Entry> entryByKey = new HashMap<String, Entry>();
    private final Collator collator = Collator.getInstance(Locale.getDefault());
    private String pendingFocusKey;

    private static class Entry {
        ComponentName cn;
        String name;
        String cacheKey;
        boolean tvApp;
        boolean systemApp;
        Intent fixedIntent;
    }

    private String lastFocusKey;

    private final Runnable clockTick = new Runnable() {
        public void run() {
            clock.setText(TIME.format(new Date()));
            ui.postDelayed(this, 10000L);
        }
    };

    private final ViewOutlineProvider roundedOutline = new ViewOutlineProvider() {
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(CARD_RADIUS_DP));
        }
    };

    private static final float SCROLL_KEEP = 0.66f;
    private static final int SCROLL_MS = 190;

    private final View.OnFocusChangeListener tileFocus = new View.OnFocusChangeListener() {
        public void onFocusChange(View v, boolean hasFocus) {
            float scale = hasFocus ? FOCUS_SCALE : 1.0f;
            v.animate().scaleX(scale).scaleY(scale).setDuration(FOCUS_ANIM_MS).start();
            v.setTranslationZ(hasFocus ? dp(SHADOW_DP) : 0f);
            View label = v.findViewById(R.id.tile_label);
            if (label != null) {
                label.animate().alpha(hasFocus ? 1f : 0f).setDuration(FOCUS_ANIM_MS).start();
            }
            if (hasFocus) {
                Object tag = v.getTag(R.id.tile_icon);
                if (tag instanceof Entry) {
                    applyWash((Entry) tag);
                }
                if (v.getParent() == grid) {
                    keepInView(v);
                }
            }
        }
    };

    private void keepInView(final View item) {
        if (grid.getHeight() == 0) {
            return;
        }
        final int keep = Math.round(grid.getHeight() * SCROLL_KEEP);
        int delta = item.getBottom() - keep;
        if (item.getTop() < 0) {
            delta = item.getTop();
        }
        if (delta == 0) {
            return;
        }
        final int dy = delta;
        grid.post(new Runnable() {
            public void run() {
                grid.smoothScrollBy(dy, SCROLL_MS);
            }
        });
    }

    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        pm = getPackageManager();
        inflater = getLayoutInflater();
        setContentView(R.layout.main);

        root = findViewById(R.id.root);
        grid = (GridView) findViewById(R.id.grid);
        shelfRow = (LinearLayout) findViewById(R.id.shelfRow);
        shelfScroll = findViewById(R.id.shelfScroll);
        favEmpty = (TextView) findViewById(R.id.favEmpty);
        clock = (TextView) findViewById(R.id.clock);
        counter = (TextView) findViewById(R.id.counter);
        filter = (EditText) findViewById(R.id.filter);

        wash = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{washTop, washBottom});
        root.setBackground(wash);

        root.getViewTreeObserver().addOnGlobalFocusChangeListener(
                new android.view.ViewTreeObserver.OnGlobalFocusChangeListener() {
                    public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                        if (newFocus == null) {
                            focusWithRetry(lastFocusKey, 6);
                            return;
                        }
                        String key = keyOf(newFocus);
                        if (key != null) {
                            lastFocusKey = key;
                            return;
                        }
                        if (newFocus == grid) {
                            focusWithRetry(lastFocusKey, 4);
                        }
                    }
                });

        adapter = new GridAdapter();
        grid.setAdapter(adapter);

        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable s) {
                rebuildGrid();
            }
        });
    }

    protected void onResume() {
        super.onResume();
        String focusKey = pendingFocusKey;
        pendingFocusKey = null;
        loadFav();
        loadRecent();
        loadApps();
        rebuildShelf();
        rebuildGrid();
        scheduleFocusRestore(focusKey);
    }

    protected void onPause() {
        pendingFocusKey = captureFocus();
        super.onPause();
    }

    protected void onStart() {
        super.onStart();
        ui.post(clockTick);
    }

    protected void onStop() {
        ui.removeCallbacks(clockTick);
        super.onStop();
    }

    public boolean onKeyDown(int code, KeyEvent ev) {
        if (code == KeyEvent.KEYCODE_MENU) {
            toggleFilter();
            return true;
        }
        if (filter.getVisibility() == View.VISIBLE) {
            return super.onKeyDown(code, ev);
        }
        View focused = getCurrentFocus();
        View shelfTile = shelfTileOf(focused);
        if (shelfTile != null && code == KeyEvent.KEYCODE_DPAD_DOWN) {
            View below = shelfTile.focusSearch(View.FOCUS_DOWN);
            if (below != null && below != shelfTile && below.requestFocus()) {
                return true;
            }
            focusGrid();
            return true;
        }
        return super.onKeyDown(code, ev);
    }

    private View shelfTileOf(View v) {
        while (v != null) {
            if (v.getParent() == shelfRow) {
                return v;
            }
            if (!(v.getParent() instanceof View)) {
                return null;
            }
            v = (View) v.getParent();
        }
        return null;
    }

    private void scheduleFocusRestore(final String key) {
        focusWithRetry(key, 14);
    }

    private void focusWithRetry(final String key, final int tries) {
        boolean ok = focusTarget(key);
        if (ok || tries <= 0) {
            if (!ok && !FOCUS_SHELF.equals(key)) {
                focusShelf();
            }
            return;
        }
        root.postDelayed(new Runnable() {
            public void run() {
                focusWithRetry(key, tries - 1);
            }
        }, 180L);
    }

    private boolean focusTarget(String key) {
        if (FOCUS_SHELF.equals(key)) {
            return focusShelf();
        }
        if (key == null) {
            return focusGrid();
        }
        for (int i = 0; i < shown.size(); i++) {
            if (!key.equals(shown.get(i).cacheKey)) {
                continue;
            }
            if (grid.getSelectedItemPosition() != i) {
                grid.setSelection(i);
            }
            grid.requestFocus();
            View target = grid.getSelectedView();
            if (target == null) {
                target = grid.getChildAt(0);
            }
            if (target != null) {
                target.requestFocus();
            }
            return gridChildFocused();
        }
        for (int i = 0; i < shelfRow.getChildCount(); i++) {
            if (keyOf(shelfRow.getChildAt(i)) != null && key.equals(keyOf(shelfRow.getChildAt(i)))) {
                return shelfRow.getChildAt(i).requestFocus();
            }
        }
        return focusGrid();
    }

    private boolean focusShelf() {
        if (shelfRow.getChildCount() == 0) {
            return false;
        }
        return shelfRow.getChildAt(0).requestFocus();
    }

    private boolean gridChildFocused() {
        View f = getCurrentFocus();
        return f != null && f.getParent() == grid;
    }

    private boolean focusGrid() {
        if (shown.isEmpty()) {
            return focusShelf();
        }
        if (gridChildFocused()) {
            return true;
        }
        if (grid.getSelectedItemPosition() < 0) {
            grid.setSelection(0);
        }
        grid.requestFocus();
        View target = grid.getSelectedView();
        if (target == null) {
            target = grid.getChildAt(0);
        }
        if (target != null) {
            target.requestFocus();
        }
        return gridChildFocused();
    }

    private String keyOf(View v) {
        while (v != null) {
            Object tag = v.getTag(R.id.tile_icon);
            if (tag instanceof Entry) {
                return ((Entry) tag).cacheKey;
            }
            if (v == shelfRow || !(v.getParent() instanceof View)) {
                return null;
            }
            v = (View) v.getParent();
        }
        return null;
    }

    private String captureFocus() {
        if (shelfRow.hasFocus()) {
            return FOCUS_SHELF;
        }
        View f = getCurrentFocus();
        while (f != null) {
            Object tag = f.getTag(R.id.tile_icon);
            if (tag instanceof Entry) {
                return ((Entry) tag).cacheKey;
            }
            if (!(f.getParent() instanceof View)) {
                break;
            }
            f = (View) f.getParent();
        }
        return null;
    }

    private void toggleFilter() {
        if (filter.getVisibility() == View.VISIBLE) {
            filter.setText("");
            filter.setVisibility(View.GONE);
            hideIme();
            focusGrid();
        } else {
            filter.setVisibility(View.VISIBLE);
            filter.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(filter, 0);
            }
        }
        rebuildGrid();
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(filter.getWindowToken(), 0);
        }
    }

    private void loadRecent() {
        recent.clear();
        String raw = getSharedPreferences(PREFS, 0).getString(KEY_RECENT, "");
        if (raw == null || raw.length() == 0) {
            return;
        }
        for (String part : raw.split("\\|")) {
            if (part.length() > 0 && !recent.contains(part)) {
                recent.add(part);
            }
        }
    }

    private void touchRecent(String key) {
        recent.remove(key);
        recent.add(0, key);
        while (recent.size() > RECENT_MAX) {
            recent.remove(recent.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (String item : recent) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(item);
        }
        getSharedPreferences(PREFS, 0).edit().putString(KEY_RECENT, sb.toString()).apply();
    }

    private void loadFav() {
        fav.clear();
        SharedPreferences sp = getSharedPreferences(PREFS, 0);
        fav.addAll(sp.getStringSet(KEY_FAV, Collections.<String>emptySet()));
    }

    private void loadApps() {
        all.clear();
        Set<String> hidden = hiddenPackages();
        Map<String, Entry> map = new LinkedHashMap<String, Entry>();
        collect(map, Intent.CATEGORY_LEANBACK_LAUNCHER, true, hidden);
        collect(map, Intent.CATEGORY_LAUNCHER, false, hidden);
        all.addAll(map.values());
        Collections.sort(all, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                if (a.systemApp != b.systemApp) {
                    return a.systemApp ? 1 : -1;
                }
                if (a.tvApp != b.tvApp) {
                    return a.tvApp ? -1 : 1;
                }
                return collator.compare(a.name, b.name);
            }
        });
    }

    private Set<String> hiddenPackages() {
        Set<String> hidden = new HashSet<String>();
        for (Entry e : fixedEntries()) {
            ResolveInfo ri = pm.resolveActivity(e.fixedIntent, 0);
            if (ri != null && ri.activityInfo != null) {
                hidden.add(ri.activityInfo.packageName);
            }
        }
        return hidden;
    }

    private void collect(Map<String, Entry> map, String category, boolean tvApps, Set<String> hidden) {
        Intent probe = new Intent(Intent.ACTION_MAIN);
        probe.addCategory(category);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) {
                continue;
            }
            String pkg = ri.activityInfo.packageName;
            if (getPackageName().equals(pkg) || hidden.contains(pkg)) {
                continue;
            }
            ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
            String key = cn.flattenToString();
            if (map.containsKey(key)) {
                continue;
            }
            Entry existing = entryByKey.get(key);
            Entry e = existing != null ? existing : new Entry();
            e.cacheKey = key;
            e.cn = cn;
            e.tvApp = tvApps;
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            e.systemApp = ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            CharSequence label = ri.loadLabel(pm);
            e.name = (label == null || label.length() == 0) ? pkg : label.toString();
            e.fixedIntent = null;
            entryByKey.put(key, e);
            map.put(key, e);
        }
    }

    private List<Entry> fixedEntries() {
        List<Entry> fixed = new ArrayList<Entry>();

        Entry settings = stableEntry("fixed:settings");
        settings.name = getString(R.string.tile_settings);
        Intent settingsIntent = new Intent(Settings.ACTION_SETTINGS);
        settings.fixedIntent = settingsIntent;
        settings.cn = null;
        fixed.add(settings);

        Entry tv = stableEntry("fixed:tv");
        tv.name = getString(R.string.tile_tv);
        Intent tvIntent = new Intent(Intent.ACTION_MAIN);
        tvIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        tvIntent.setClassName(LIVE_TV_PKG, LIVE_TV_CLS);
        tv.fixedIntent = tvIntent;
        tv.cn = null;
        fixed.add(tv);

        return fixed;
    }

    private Entry stableEntry(String key) {
        Entry e = entryByKey.get(key);
        if (e == null) {
            e = new Entry();
            e.cacheKey = key;
            entryByKey.put(key, e);
        }
        return e;
    }

    private void rebuildShelf() {
        shelfRow.removeAllViews();
        List<Entry> shelf = new ArrayList<Entry>();
        shelf.addAll(fixedEntries());
        for (Entry e : all) {
            if (fav.contains(e.cacheKey)) {
                shelf.add(e);
            }
        }
        for (String key : recent) {
            for (Entry e : all) {
                if (e.cacheKey.equals(key) && !containsEntry(shelf, e)) {
                    shelf.add(e);
                    break;
                }
            }
        }
        for (Entry e : shelf) {
            shelfRow.addView(makeShelfTile(e));
        }
        if (DEBUG) {
            StringBuilder sb = new StringBuilder();
            for (Entry e : shelf) {
                sb.append('[').append(e.name).append(' ').append(e.cacheKey).append(']');
            }
            logi("shelf=" + sb);
        }
        favEmpty.setVisibility(fav.isEmpty() && recent.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private boolean containsEntry(List<Entry> list, Entry e) {
        for (Entry item : list) {
            if (item.cacheKey.equals(e.cacheKey)) {
                return true;
            }
        }
        return false;
    }

    private void rebuildGrid() {
        String mask = filter.getText().toString().trim().toLowerCase(Locale.getDefault());
        shown.clear();
        for (Entry e : all) {
            if (mask.length() == 0 || e.name.toLowerCase(Locale.getDefault()).contains(mask)) {
                shown.add(e);
            }
        }
        counter.setVisibility(mask.length() == 0 ? View.GONE : View.VISIBLE);
        counter.setText(getString(R.string.apps_count, shown.size()));
        adapter.notifyDataSetChanged();
    }

    private View makeShelfTile(Entry e) {
        View v = inflater.inflate(R.layout.shelf_tile, shelfRow, false);
        bindTile(v, e);
        return v;
    }

    private void bindTile(View v, final Entry e) {
        v.setTag(R.id.tile_icon, e);
        v.setOutlineProvider(roundedOutline);

        TextView label = (TextView) v.findViewById(R.id.tile_label);
        if (label != null) {
            label.setText(e.name);
            label.setAlpha(v.isFocused() ? 1f : 0f);
        }

        ImageView icon = (ImageView) v.findViewById(R.id.tile_icon);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(roundedOutline);
        bindIcon(icon, e);

        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                launch(e);
            }
        });
        v.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View view) {
                showMenu(e);
                return true;
            }
        });
        v.setOnFocusChangeListener(tileFocus);

        float scale = v.isFocused() ? FOCUS_SCALE : 1.0f;
        v.setScaleX(scale);
        v.setScaleY(scale);
        v.setTranslationZ(v.isFocused() ? dp(SHADOW_DP) : 0f);
    }

    private void bindIcon(final ImageView icon, final Entry e) {
        icon.setTag(e);
        Bitmap cached = iconCache.get(e.cacheKey);
        if (cached != null) {
            icon.setImageBitmap(cached);
            return;
        }
        registerWaiter(e.cacheKey, icon);
        if (!iconInFlight.add(e.cacheKey)) {
            return;
        }
        final Entry target = e;
        iconLoader.execute(new Runnable() {
            public void run() {
                final Bitmap result = loadIconBitmap(target);
                ui.post(new Runnable() {
                    public void run() {
                        iconInFlight.remove(target.cacheKey);
                        if (result == null) {
                            return;
                        }
                        iconCache.put(target.cacheKey, result);
                        ArrayList<ImageView> waiters = takeWaiters(target.cacheKey);
                        for (ImageView view : waiters) {
                            if (view.getTag() == target) {
                                view.setImageBitmap(result);
                            }
                        }
                        View focused = getCurrentFocus();
                        while (focused != null) {
                            Object tag = focused.getTag(R.id.tile_icon);
                            if (tag instanceof Entry) {
                                if (tag == target) {
                                    applyWash(target);
                                }
                                break;
                            }
                            if (!(focused.getParent() instanceof View)) {
                                break;
                            }
                            focused = (View) focused.getParent();
                        }
                    }
                });
            }
        });
    }

    private void registerWaiter(String key, ImageView view) {
        ArrayList<WeakReference<ImageView>> list = pendingViews.get(key);
        if (list == null) {
            list = new ArrayList<WeakReference<ImageView>>();
            pendingViews.put(key, list);
        }
        list.add(new WeakReference<ImageView>(view));
    }

    private ArrayList<ImageView> takeWaiters(String key) {
        ArrayList<ImageView> alive = new ArrayList<ImageView>();
        ArrayList<WeakReference<ImageView>> list = pendingViews.remove(key);
        if (list == null) {
            return alive;
        }
        for (WeakReference<ImageView> ref : list) {
            ImageView view = ref.get();
            if (view != null) {
                alive.add(view);
            }
        }
        return alive;
    }

    private Bitmap loadIconBitmap(Entry e) {
        Bitmap icon = null;
        try {
            Drawable d = resolveIconDrawable(e);
            if (d == null) {
                return monogramArt(e);
            }
            int raw = 192;
            icon = Bitmap.createBitmap(raw, raw, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(icon);
            int overscan = Math.round(raw * 0.12f);
            d.setBounds(-overscan, -overscan, raw + overscan, raw + overscan);
            d.draw(canvas);
            Integer tint = vibrantTint(icon);
            Integer edge = edgeColor(icon);
            if (tint == null && edge == null) {
                return monogramArt(e);
            }
            Integer field = edge != null ? edge : tint;
            if (tint != null) {
                tintCache.put(e.cacheKey, tint);
            }
            return composeArt(icon, field == null ? ART_FALLBACK_TINT : field.intValue());
        } catch (Exception t) {
            logw("icon load failed: " + e.cacheKey, t);
            return monogramArt(e);
        } catch (Error t) {
            logw("icon load error: " + e.cacheKey, t);
            return monogramArt(e);
        } finally {
            if (icon != null) {
                icon.recycle();
            }
        }
    }

    private Drawable resolveIconDrawable(Entry e) {
        Drawable d = null;
        if (e.cn != null) {
            try {
                d = pm.getActivityIcon(e.cn);
            } catch (Exception ignored) {
            }
            if (d == null) {
                try {
                    d = pm.getApplicationIcon(e.cn.getPackageName());
                } catch (Exception ignored) {
                }
            }
        }
        if (d == null && e.fixedIntent != null) {
            try {
                ResolveInfo ri = pm.resolveActivity(e.fixedIntent, 0);
                if (ri != null) {
                    d = ri.loadIcon(pm);
                }
            } catch (Exception ignored) {
            }
        }
        return d;
    }

    private Bitmap monogramArt(Entry e) {
        Bitmap art = Bitmap.createBitmap(ART_W, ART_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(art);
        canvas.drawColor(0xFF262C36);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFF98A1B2);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(ART_H * 0.46f);
        String name = e.name == null ? "" : e.name.trim();
        String letter = name.length() == 0 ? "?" : name.substring(0, 1).toUpperCase(Locale.getDefault());
        canvas.drawText(letter, ART_W / 2f, ART_H / 2f - (paint.ascent() + paint.descent()) / 2f, paint);
        return art;
    }

    private Bitmap composeArt(Bitmap icon, int field) {
        Bitmap art = Bitmap.createBitmap(ART_W, ART_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(art);
        canvas.drawColor(field);
        Paint blur = new Paint(Paint.FILTER_BITMAP_FLAG);
        Bitmap mid = Bitmap.createScaledBitmap(icon, 64, 64, true);
        Bitmap tiny = Bitmap.createScaledBitmap(mid, 16, 8, true);
        mid.recycle();
        canvas.drawBitmap(tiny, null, new Rect(-30, -30, ART_W + 30, ART_H + 30), blur);
        if (tiny != icon) {
            tiny.recycle();
        }
        canvas.drawColor(0x40000000);

        int side = Math.round(ART_H * ICON_BLEED);
        int left = (ART_W - side) / 2;
        int top = (ART_H - side) / 2;
        float radius = side * 0.14f;
        RectF cardRect = new RectF(left, top, left + side, top + side);

        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0xFF0B0B0D);
        shadow.setShadowLayer(16f, 0f, 7f, 0x99000000);
        canvas.drawRoundRect(cardRect, radius, radius, shadow);

        int saved = canvas.save();
        Path clip = new Path();
        clip.addRoundRect(cardRect, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.drawBitmap(icon, new Rect(0, 0, icon.getWidth(), icon.getHeight()),
                new Rect(left, top, left + side, top + side), null);
        canvas.restoreToCount(saved);

        int lum = (((field >> 16) & 0xFF) * 299 + ((field >> 8) & 0xFF) * 587 + (field & 0xFF) * 114) / 1000;
        if (lum > 170) {
            canvas.drawColor(0x33CFD3DC);
        }

        GradientDrawable scrim = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x2E000000});
        scrim.setBounds(0, 0, ART_W, ART_H);
        scrim.draw(canvas);
        return art;
    }

    private Integer edgeColor(Bitmap src) {
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            int patch = Math.max(4, Math.min(w, h) / 10);
            int[] xy = new int[]{0, 0, w - patch, 0, 0, h - patch, w - patch, h - patch, (w - patch) / 2, 0, (w - patch) / 2, h - patch};
            long r = 0;
            long g = 0;
            long b = 0;
            int used = 0;
            for (int i = 0; i < xy.length; i += 2) {
                int[] px = new int[patch * patch];
                src.getPixels(px, 0, patch, xy[i], xy[i + 1], patch, patch);
                for (int pixel : px) {
                    if (((pixel >>> 24) & 0xFF) < 220) {
                        continue;
                    }
                    r += (pixel >> 16) & 0xFF;
                    g += (pixel >> 8) & 0xFF;
                    b += pixel & 0xFF;
                    used++;
                }
            }
            if (used == 0) {
                return null;
            }
            return Integer.valueOf(Color.rgb((int) (r / used), (int) (g / used), (int) (b / used)));
        } catch (Exception t) {
            logw("edge color sampling failed", t);
            return null;
        }
    }

    private Integer vibrantTint(Bitmap src) {
        try {
            int side = Math.min(32, Math.min(src.getWidth(), src.getHeight()));
            int[] px = new int[side * side];
            src.getPixels(px, 0, side, 0, 0, side, side);
            long r = 0;
            long g = 0;
            long b = 0;
            int n = 0;
            for (int p : px) {
                if (((p >>> 24) & 0xFF) < 200) {
                    continue;
                }
                int rr = (p >> 16) & 0xFF;
                int gg = (p >> 8) & 0xFF;
                int bb = p & 0xFF;
                int max = Math.max(rr, Math.max(gg, bb));
                int min = Math.min(rr, Math.min(gg, bb));
                if (max < 70 || (max - min) / (float) max < 0.32f) {
                    continue;
                }
                r += rr;
                g += gg;
                b += bb;
                n++;
            }
            if (n < 10) {
                return averageTint(src);
            }
            return Integer.valueOf(Color.rgb((int) (r / n), (int) (g / n), (int) (b / n)));
        } catch (Exception t) {
            logw("vibrant color sampling failed", t);
            return averageTint(src);
        }
    }

    private Integer averageTint(Bitmap src) {
        try {
            int side = Math.min(24, Math.min(src.getWidth(), src.getHeight()));
            int[] pixels = new int[side * side];
            src.getPixels(pixels, 0, side, 0, 0, side, side);
            long r = 0;
            long g = 0;
            long b = 0;
            int used = 0;
            for (int pixel : pixels) {
                if (((pixel >>> 24) & 0xFF) < 40) {
                    continue;
                }
                r += (pixel >> 16) & 0xFF;
                g += (pixel >> 8) & 0xFF;
                b += pixel & 0xFF;
                used++;
            }
            if (used == 0) {
                return null;
            }
            return Integer.valueOf(Color.rgb((int) (r / used), (int) (g / used), (int) (b / used)));
        } catch (Exception t) {
            logw("average color sampling failed", t);
            return null;
        }
    }

    private Integer tintFor(Entry e) {
        Integer cached = tintCache.get(e.cacheKey);
        return cached;
    }

    private void applyWash(Entry e) {
        Integer tint = tintFor(e);
        int top;
        int bottom;
        if (tint == null) {
            top = WASH_TOP_DEFAULT;
            bottom = WASH_BOTTOM_DEFAULT;
        } else {
            int value = tint.intValue();
            int lum = (((value >> 16) & 0xFF) * 299 + ((value >> 8) & 0xFF) * 587 + (value & 0xFF) * 114) / 1000;
            float topKeep = lum > 150 ? 0.16f : 0.30f;
            float bottomKeep = lum > 150 ? 0.05f : 0.08f;
            top = darken(value, topKeep);
            bottom = darken(value, bottomKeep);
        }
        animateWash(top, bottom);
    }

    private int darken(int color, float keep) {
        int r = Math.round(((color >> 16) & 0xFF) * keep);
        int g = Math.round(((color >> 8) & 0xFF) * keep);
        int b = Math.round((color & 0xFF) * keep);
        return Color.rgb(r, g, b);
    }

    private void animateWash(final int targetTop, final int targetBottom) {
        if (washTop == targetTop && washBottom == targetBottom) {
            return;
        }
        if (washAnim != null) {
            washAnim.cancel();
        }
        final int fromTop = washTop;
        final int fromBottom = washBottom;
        final int[] colors = new int[2];
        washAnim = ValueAnimator.ofFloat(0f, 1f);
        washAnim.setDuration(WASH_MS);
        washAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator animator) {
                float f = ((Float) animator.getAnimatedValue()).floatValue();
                colors[0] = blendArgb(fromTop, targetTop, f);
                colors[1] = blendArgb(fromBottom, targetBottom, f);
                washTop = colors[0];
                washBottom = colors[1];
                wash.setColors(colors);
            }
        });
        washAnim.start();
    }

    private int blendArgb(int from, int to, float f) {
        int fromA = (from >>> 24) & 0xFF;
        int fromR = (from >> 16) & 0xFF;
        int fromG = (from >> 8) & 0xFF;
        int fromB = from & 0xFF;
        int toA = (to >>> 24) & 0xFF;
        int toR = (to >> 16) & 0xFF;
        int toG = (to >> 8) & 0xFF;
        int toB = to & 0xFF;
        return Color.argb(
                Math.round(fromA + (toA - fromA) * f),
                Math.round(fromR + (toR - fromR) * f),
                Math.round(fromG + (toG - fromG) * f),
                Math.round(fromB + (toB - fromB) * f));
    }

    private void launch(Entry e) {
        try {
            if (e.fixedIntent != null) {
                Intent intent = new Intent(e.fixedIntent);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            }
            Intent launchIntent = pm.getLaunchIntentForPackage(e.cn.getPackageName());
            if (launchIntent == null) {
                launchIntent = new Intent(Intent.ACTION_MAIN);
                launchIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                launchIntent.setComponent(e.cn);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            touchRecent(e.cacheKey);
            startActivity(launchIntent);
        } catch (Exception ex) {
            hideIme();
        }
    }

    private void showMenu(final Entry e) {
        if (e.cn == null) {
            return;
        }
        final boolean isFav = fav.contains(e.cacheKey);
        String[] items = new String[]{
                getString(isFav ? R.string.menu_fav_del : R.string.menu_fav_add),
                getString(R.string.menu_details)
        };
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            toggleFav(e);
                        } else {
                            showAppDetails(e);
                        }
                    }
                })
                .show();
    }

    private void toggleFav(Entry e) {
        if (fav.contains(e.cacheKey)) {
            fav.remove(e.cacheKey);
        } else {
            fav.add(e.cacheKey);
        }
        Set<String> set = new HashSet<String>(fav);
        getSharedPreferences(PREFS, 0).edit().putStringSet(KEY_FAV, set).apply();
        rebuildShelf();
    }

    private void showAppDetails(Entry e) {
        if (e.cn == null) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", e.cn.getPackageName(), null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private static void logi(String message) {
        if (DEBUG) {
            android.util.Log.i("TigrLauncher", message);
        }
    }

    private static void logw(String message, Throwable error) {
        if (DEBUG) {
            android.util.Log.w("TigrLauncher", message, error);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class GridAdapter extends BaseAdapter {
        public int getCount() {
            return shown.size();
        }

        public Object getItem(int pos) {
            return shown.get(pos);
        }

        public long getItemId(int pos) {
            return pos;
        }

        public boolean hasStableIds() {
            return false;
        }

        public View getView(int pos, View recycled, ViewGroup parent) {
            View v = recycled != null ? recycled : inflater.inflate(R.layout.tile, parent, false);
            bindTile(v, shown.get(pos));
            return v;
        }
    }
}
